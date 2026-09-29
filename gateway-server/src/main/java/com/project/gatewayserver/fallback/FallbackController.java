package com.project.gatewayserver.fallback;

import com.project.gatewayserver.i18n.GatewayText;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;

/**
 * 서킷브레이커 폴백.
 *
 * <p><b>⚠️ 타임아웃 판정에 클래스가 두 개다.</b> 예전에는 Spring Cloud Gateway 의
 * {@link TimeoutException} 만 봤다. 그런데 이 게이트웨이에서 실제로 나는 타임아웃은
 * Resilience4j TimeLimiter 가 던지는 {@link java.util.concurrent.TimeoutException}
 * (리액터의 구현체)이다. 서로 다른 클래스라 {@code instanceof} 가 맞지 않았고, <b>모든
 * 타임아웃이 기본 분기로 떨어져</b> 503 "내부 서버 오류"로 뭉뚱그려졌다.
 *
 * <p>그 한 줄 때문에 진단이 세 번 왕복했다. 실제 사례: {@code /api/content/home} 이
 * 파드 직접 호출로는 0.3초에 200 인데 게이트웨이로는 503 "내부 서버 오류"가 나왔다.
 * 504 "시간이 초과되었습니다"였다면 첫 화면에서 원인이 보였을 것이다.
 * 게이트웨이 설정의 {@code record-exceptions} 에도 {@code java.util.concurrent.TimeoutException}
 * 이 적혀 있다 — 설정은 이미 알고 있었고 이 코드만 몰랐다.
 *
 * <p>둘 다 본다. 어느 쪽이든 사용자에게는 같은 사실("늦어서 끊었다")이다.
 */
@RestController
@Slf4j
public class FallbackController {

    @RequestMapping("/fallback")
    public Mono<ResponseEntity<String>> fallback(ServerWebExchange exchange) {
        // CircuitBreaker에서 발생한 예외를 가져옵니다.
        Throwable exception = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        String errorMessage = GatewayText.pick(exchange, "내부 서버 오류가 발생했습니다. 관리자에게 문의해 주세요.", "内部サーバーエラーが発生しました。管理者にお問い合わせください。");
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;

        // 예외 종류에 따라 로그와 응답 메시지를 다르게 처리합니다.
        if (exception != null) {
            // 예외 타입도 함께 남긴다. 메시지만으로는 어느 계층에서 끊겼는지 알 수 없다.
            log.warn("Fallback triggered. Exception: {}: {}",
                    exception.getClass().getName(), exception.getMessage());

            if (exception instanceof NotFoundException) {
                errorMessage = GatewayText.pick(exchange, "요청한 서비스를 찾을 수 없습니다.", "リクエストされたサービスが見つかりません。");
                status = HttpStatus.NOT_FOUND;
            } else if (isTimeout(exception)) {
                errorMessage = GatewayText.pick(exchange, "서비스 요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.", "サービスの応答がタイムアウトしました。しばらくしてから再度お試しください。");
                status = HttpStatus.GATEWAY_TIMEOUT;
            } else if (exception instanceof CallNotPermittedException) {
                errorMessage = GatewayText.pick(exchange, "서비스가 현재 사용 불가능합니다. 잠시 후 다시 시도해 주세요.", "サービスは現在ご利用いただけません。しばらくしてから再度お試しください。");
                status = HttpStatus.SERVICE_UNAVAILABLE;
            }
        } else {
            log.warn("Fallback triggered with no specific exception.");
        }

        return Mono.just(new ResponseEntity<>(errorMessage, status));
    }

    /**
     * 타임아웃인가. 두 계층을 모두 본다 — 게이트웨이 자체 타임아웃과 Resilience4j
     * TimeLimiter 의 타임아웃은 이름만 같고 클래스가 다르다(클래스 주석 참조).
     */
    static boolean isTimeout(Throwable exception) {
        return exception instanceof TimeoutException
                || exception instanceof java.util.concurrent.TimeoutException;
    }
}