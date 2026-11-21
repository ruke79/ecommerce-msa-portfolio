package com.project.common.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.exception.UpstreamCallException;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 서비스 간 Feign 서킷 브레이커(4단계 ④) — 상대가 아픈 실패(연결 · 시간 초과 · 5xx · 429)만 세고, 같은 스레드에서 원래 예외를 그대로
 * 던지며, 열리면 상대를 부르지 않고 바로 {@link CallNotPermittedException}.
 * [JA] サービス間 Feign のサーキットブレーカー — 相手の不調だけを数え、同じスレッドで元の例外をそのまま投げ、開いたら相手を呼ばずすぐ失敗する。
 */
@DisplayName("UpstreamCircuitBreakers — 같은 스레드 · 원래 예외 · 상대가 아플 때만 연다")
class UpstreamCircuitBreakersTest {

    interface Client {
        String get(String id);
    }

    private static final Request REQ = Request.create(Request.HttpMethod.GET, "http://x/y", Map.of(), new byte[0],
            StandardCharsets.UTF_8, null);

    private static FeignException status(int s) {
        return FeignException.errorStatus("Client#get", feign.Response.builder().status(s).reason("r").request(REQ)
                .headers(Map.of()).build());
    }

    @Test
    @DisplayName("🔴 실패로 세는 것 — 연결 실패 · 5xx · 429 · 원인 사슬, 4xx 는 세지 않는다")
    void classifiesUpstreamFailures() {
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(new RetryableException(-1, "refused", Request.HttpMethod.GET, (Long) null, REQ))).isTrue();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(status(503))).isTrue();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(status(429))).isTrue();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(new UpstreamCallException("m", 502))).isTrue();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(new IllegalStateException("w", status(500)))).as("원인 사슬").isTrue();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(status(404))).isFalse();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(status(400))).isFalse();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(new UpstreamCallException("m", 409))).isFalse();
        assertThat(UpstreamCircuitBreakers.isUpstreamFailure(new IllegalArgumentException("bad"))).isFalse();
    }

    @Test
    @DisplayName("🔴 5xx 가 쌓이면 열린다 — 그다음은 상대를 부르지 않고 바로 CallNotPermittedException, 상태 지표 1")
    void opensOnUpstreamFailures() {
        AtomicInteger calls = new AtomicInteger();
        Client target = id -> {
            calls.incrementAndGet();
            throw status(503);
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreaker cb = UpstreamCircuitBreakers.breaker("t", UpstreamCircuitBreakers.config(Duration.ofMinutes(5)), meters);
        Client c = UpstreamCircuitBreakers.wrap(Client.class, target, cb);

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> c.get("a")).isInstanceOf(FeignException.ServiceUnavailable.class);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> c.get("a")).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls.get()).as("열린 뒤에는 부르지 않는다").isEqualTo(10);
        assertThat(meters.get(UpstreamCircuitBreakers.STATE_METRIC).tag("name", "t").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("🔴 4xx 는 아무리 많아도 열지 않는다 — 원래 예외(FeignException.NotFound) 그대로, 같은 스레드에서 부른다")
    void clientErrorsKeepItClosedAndPassThrough() {
        AtomicReference<Thread> seen = new AtomicReference<>();
        Client target = id -> {
            seen.set(Thread.currentThread());
            throw status(404);
        };
        CircuitBreaker cb = UpstreamCircuitBreakers.breaker("t4", UpstreamCircuitBreakers.defaultConfig(), null);
        Client c = UpstreamCircuitBreakers.wrap(Client.class, target, cb);

        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> c.get("a")).isExactlyInstanceOf(FeignException.NotFound.class);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(seen.get()).as("TimeLimiter 의 다른 스레드가 아니다 — RequestContextHolder 의 헤더가 이어진다").isSameAs(Thread.currentThread());
    }

    @Test
    @DisplayName("성공 값은 그대로 · Object 메서드는 브레이커를 지나지 않는다")
    void passesValuesThrough() {
        Client c = UpstreamCircuitBreakers.wrap(Client.class, id -> "v-" + id,
                UpstreamCircuitBreakers.breaker("ok", UpstreamCircuitBreakers.defaultConfig(), null));
        assertThat(c.get("1")).isEqualTo("v-1");
        assertThat(c.toString()).startsWith("CircuitBreaker(");
        assertThat(c).isEqualTo(c);
    }
}
