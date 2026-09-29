package com.project.gatewayserver.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * 폴백이 원인을 <b>구분해서</b> 알려 주는가.
 *
 * <p>이 테스트가 존재하는 이유: 예전에는 Spring Cloud Gateway 의 {@code TimeoutException}
 * 만 보고 있었는데, 이 게이트웨이에서 실제로 나는 타임아웃은 Resilience4j TimeLimiter 가
 * 던지는 {@code java.util.concurrent.TimeoutException} 이었다. 클래스가 달라 분기가
 * 맞지 않았고, 모든 타임아웃이 503 "내부 서버 오류"로 뭉뚱그려졌다.
 *
 * <p>증상 쪽에서 보면 이렇다 — 파드를 직접 부르면 0.3초에 200 인데 게이트웨이로는
 * "내부 서버 오류"가 난다. 원인이 타임아웃이라는 단서가 응답 어디에도 없다.
 */
class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    private ResponseEntity<String> fallbackFor(Throwable cause) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/fallback"));
        if (cause != null) {
            exchange.getAttributes()
                    .put(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR, cause);
        }
        return controller.fallback(exchange).block();
    }

    @Test
    @DisplayName("Resilience4j TimeLimiter 의 타임아웃도 504 로 분류한다 (예전에는 503 '내부 서버 오류'였다)")
    void concurrentTimeoutIsGatewayTimeout() {
        // 리액터가 실제로 던지는 메시지 그대로.
        ResponseEntity<String> response = fallbackFor(new java.util.concurrent.TimeoutException(
                "Did not observe any item or terminal signal within 1000ms in 'circuitBreaker'"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).contains("시간이 초과");
    }

    @Test
    @DisplayName("게이트웨이 자체 타임아웃도 여전히 504")
    void gatewayTimeoutIsGatewayTimeout() {
        ResponseEntity<String> response = fallbackFor(new TimeoutException("response timeout"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("서킷이 열려 거부된 호출은 503 이되 문구가 다르다 — 타임아웃과 구분되어야 한다")
    void callNotPermittedIsServiceUnavailable() {
        io.github.resilience4j.circuitbreaker.CircuitBreaker breaker =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("test");
        breaker.transitionToOpenState();

        ResponseEntity<String> response = fallbackFor(
                io.github.resilience4j.circuitbreaker.CallNotPermittedException
                        .createCallNotPermittedException(breaker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("사용 불가능");
    }

    @Test
    @DisplayName("알 수 없는 예외만 기본 분기(503 '내부 서버 오류')로 남는다")
    void unknownCauseKeepsGenericMessage() {
        ResponseEntity<String> response = fallbackFor(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("내부 서버 오류");
    }
}
