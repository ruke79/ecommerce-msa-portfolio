package com.project.common.feign;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.project.common.exception.UpstreamCallException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 서비스 간 Feign 호출의 서킷 브레이커(4단계 ④ 1차 — cart→catalog · order→catalog · order→coupon).
 *
 * <p><b>왜 Spring Cloud 의 Feign 서킷 브레이커({@code spring.cloud.openfeign.circuitbreaker.enabled})가 아닌가</b> — 그 방식은
 * ① 호출을 TimeLimiter 의 다른 스레드에서 돌려 {@code RequestContextHolder} 로 옮기던 {@code Authorization} 헤더가 끊기고(application-common.yml
 * 의 보류 메모), ② 기본 시간 상한 1초가 Feign 읽기 상한 5초보다 짧고, ③ 실패를 {@code NoFallbackAvailableException} 으로 감싸
 * {@code catch (FeignException.NotFound)} 같은 기존 분기를 깨뜨린다. 여기서는 클라이언트 인터페이스를 <b>같은 스레드</b>에서 감싸고
 * <b>원래 예외를 그대로</b> 다시 던진다 — 부르는 쪽의 실패 처리(503 · 열린 채 진행 · 400)는 그대로이고, 열린 동안은
 * {@code CallNotPermittedException}(RuntimeException)이 같은 길을 빠르게 탄다.
 *
 * <p><b>무엇을 실패로 세나</b> — 상대가 아픈 것만: 연결 실패 · 시간 초과({@code feign.RetryableException}), 5xx · 429
 * ({@code FeignException} · {@link UpstreamCallException}). 4xx 는 상대가 건강하게 거절한 것이라 세지 않는다(성공으로 친다).
 *
 * <p>설정: 최근 20번 중 10번 이상 불렀고 실패율 50% 이상이면 열린다 → 30초 뒤 반쯤 열려 3번 시험. 지표 {@code upstream.circuit.state}
 * (0 닫힘 · 1 열림 · 2 반열림) · 상태 전이는 WARN 한 줄.
 *
 * <p>[JA] サービス間 Feign 呼び出しのサーキットブレーカー。Spring Cloud の方式は別スレッドで回して Authorization ヘッダーの受け渡しが
 *      切れ、例外を包んで既存の分岐を壊すので、ここではクライアントのインターフェースを同じスレッドで包み、元の例外をそのまま投げ直す。
 *      失敗として数えるのは相手が不調のときだけ(接続失敗 · タイムアウト · 5xx · 429)。4xx は数えない。
 */
public final class UpstreamCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCircuitBreakers.class);

    public static final String STATE_METRIC = "upstream.circuit.state";

    private UpstreamCircuitBreakers() {
    }

    /** 상대가 아픈 실패인가 — 원인 사슬을 따라간다. [JA] 相手の不調による失敗か — 原因の連鎖をたどる。 */
    public static boolean isUpstreamFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof feign.RetryableException) {
                return true;
            }
            if (c instanceof feign.FeignException f) {
                return f.status() >= 500 || f.status() == 429 || f.status() < 0;
            }
            if (c instanceof UpstreamCallException u) {
                return u.getStatus() >= 500 || u.getStatus() == 429;
            }
            if (c instanceof java.net.ConnectException || c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public static CircuitBreakerConfig defaultConfig() {
        return config(Duration.ofSeconds(30));
    }

    static CircuitBreakerConfig config(Duration openFor) {
        Predicate<Throwable> record = UpstreamCircuitBreakers::isUpstreamFailure;
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(record)
                .build();
    }

    /** 이름 하나의 브레이커 — 상태 지표 · 전이 로그를 붙인다. [JA] 名前一つのブレーカー — 状態指標 · 遷移ログ付き。 */
    public static CircuitBreaker breaker(String name, CircuitBreakerConfig config, MeterRegistry meters) {
        CircuitBreaker cb = CircuitBreaker.of(name, config);
        cb.getEventPublisher().onStateTransition(e -> log.warn("상류 서킷 브레이커 {}: {}", name, e.getStateTransition()));
        if (meters != null) {
            Gauge.builder(STATE_METRIC, cb, b -> switch (b.getState()) {
                case OPEN, FORCED_OPEN -> 1;
                case HALF_OPEN -> 2;
                default -> 0;
            }).tag("name", name).description("0 닫힘 · 1 열림 · 2 반열림").register(meters);
        }
        return cb;
    }

    /**
     * 클라이언트 인터페이스의 모든 메서드를 같은 스레드에서 브레이커로 감싼 프록시 — 원래 예외를 그대로 던진다.
     * [JA] クライアントのすべてのメソッドを同じスレッドでブレーカーに通すプロキシ — 元の例外をそのまま投げる。
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> type, T target, CircuitBreaker cb) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, target, method, args);
            }
            return cb.executeCheckedSupplier(() -> {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        });
    }

    private static Object objectMethod(Object proxy, Object target, Method method, Object[] args) throws Exception {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "CircuitBreaker(" + target + ")";
            default -> method.invoke(target, args);
        };
    }
}
