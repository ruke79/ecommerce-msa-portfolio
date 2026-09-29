package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 🔒 서비스의 actuator·API 명세를 게이트웨이 밖으로 내보내지 않는다 (docs/57 §7).
 */
@DisplayName("InternalEndpointBlockFilter — 관리·문서 엔드포인트 차단")
class InternalEndpointBlockFilterTest {

    @Test
    @DisplayName("🔴 공개 catalog 라우트로 actuator·api-docs·swagger 에 닿지 못한다")
    void blocksManagementAndDocs() {
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/actuator/prometheus")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/actuator/metrics/jvm.memory.used")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/v3/api-docs")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/order-service/swagger-ui/index.html")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/user-service/swagger-ui.html")).isTrue();
    }

    @Test
    @DisplayName("🔴 인코딩·겹친 슬래시·대소문자·매트릭스 변수로 돌아가지 못한다")
    void blocksEvasions() {
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/%61ctuator/prometheus")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service//actuator/prometheus")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("//catalog-service/actuator/health")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/Actuator/prometheus")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/actuator;x=1/prometheus")).isTrue();
    }

    @Test
    @DisplayName("업무 API 와 게이트웨이 자신의 actuator 는 그대로")
    void allowsBusinessAndGatewayOwn() {
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/api/product/1")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service/api/actuator-like-name")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlocked("/user-service/api/auth/public/signin")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlocked("/actuator/prometheus")).isFalse();   // 게이트웨이 자신 — Prometheus 가 긁는다
        assertThat(InternalEndpointBlockFilter.isBlocked("/catalog-service")).isFalse();
    }

    @Test
    @DisplayName("막힌 요청은 404 로 끝나고 뒤로 넘기지 않는다")
    void respondsNotFound() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange blocked = MockServerWebExchange.from(
                MockServerHttpRequest.get("/catalog-service/actuator/prometheus").build());

        new InternalEndpointBlockFilter().filter(blocked, chain).block();

        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chain, never()).filter(any());

        MockServerWebExchange ok = MockServerWebExchange.from(MockServerHttpRequest.get("/catalog-service/api/product/1").build());
        new InternalEndpointBlockFilter().filter(ok, chain).block();
        verify(chain).filter(ok);
    }
}
