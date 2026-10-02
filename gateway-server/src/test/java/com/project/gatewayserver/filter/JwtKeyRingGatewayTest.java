package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/** 게이트웨이도 kid 로 검증 키를 고른다 — 전환 뒤 새 토큰 · 옛 토큰 모두 통과, 은퇴 · 모르는 kid 는 401(docs/68 §17). */
@DisplayName("게이트웨이 JWT — 키 링")
class JwtKeyRingGatewayTest {

    private static final String LEGACY = "gateway-test-secret-gateway-test-secret-01";
    private static final String K2 = "k2-secret-k2-secret-k2-secret-k2-secret-001";

    private static String token(String kid, String secret) {
        var b = Jwts.builder();
        if (kid != null) b.header().keyId(kid).and();
        return b.subject("a@example.com").claim("uid", 7L).claim("role", "ROLE_USER").claim("tokenType", "access")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    private static boolean passes(String token, boolean acceptLegacy) {
        JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(factory, "secretKey", LEGACY);
        ReflectionTestUtils.setField(factory, "jwtSigningKeys", "k2=" + K2);
        ReflectionTestUtils.setField(factory, "jwtActiveKid", "k2");
        ReflectionTestUtils.setField(factory, "jwtAcceptLegacy", acceptLegacy);
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/order-service/api/order/list").header("Authorization", "Bearer " + token));
        filter.filter(exchange, chain).block();
        boolean passed = exchange.getResponse().getStatusCode() != HttpStatus.UNAUTHORIZED;
        if (!passed) {
            verify(chain, never()).filter(any());
        }
        return passed;
    }

    @Test
    @DisplayName("🔴 전환 뒤 — 새 토큰(k2) · 전환 전 토큰(legacy · kid 없음) 모두 통과")
    void afterActivate() {
        assertThat(passes(token("k2", K2), true)).isTrue();
        assertThat(passes(token("legacy", LEGACY), true)).isTrue();
        assertThat(passes(token(null, LEGACY), true)).isTrue();
    }

    @Test
    @DisplayName("🔴 legacy 은퇴 · 모르는 kid · kid 와 다른 키로 서명 — 401")
    void rejected() {
        assertThat(passes(token(null, LEGACY), false)).isFalse();
        assertThat(passes(token("k9", K2), true)).isFalse();
        assertThat(passes(token("k2", LEGACY), true)).isFalse();
    }
}
