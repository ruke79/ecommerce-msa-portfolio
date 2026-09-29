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

/**
 * 🔒 게이트웨이는 <b>액세스 토큰만</b> 신원으로 받는다.
 *
 * <p>🐛 예전에는 서명·만료만 봤다. 리프레시 토큰은 같은 키로 서명되고 같은 클레임(uid·role)을 가졌으므로, 14일짜리 리프레시
 * 토큰을 Bearer 로 보내면 통과했다 — 로그아웃한 뒤에도(로그아웃은 Redis 의 리프레시 기록만 지운다).
 */
@DisplayName("게이트웨이 JWT — 토큰 종류")
class JwtAccessTokenTypeTest {

    private static final String SECRET = "gateway-test-secret-gateway-test-secret-01";

    private static String token(String type) {
        var b = Jwts.builder().subject("a@example.com").claim("uid", 7L).claim("role", "ROLE_USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000));
        if (type != null) b.claim("tokenType", type);
        return b.signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    private static HttpStatus run(String token, GatewayFilterChain chain) {
        JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(factory, "secretKey", SECRET);
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/order-service/api/order/list").header("Authorization", "Bearer " + token));
        filter.filter(exchange, chain).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    @DisplayName("액세스 토큰은 통과한다")
    void accessPasses() {
        GatewayFilterChain chain = chain();
        run(token("access"), chain);
        verify(chain).filter(any());
    }

    @Test
    @DisplayName("🔴 리프레시 토큰은 Bearer 로 보내도 401 — 하위 서비스에 닿지 않는다")
    void refreshRejected() {
        GatewayFilterChain chain = chain();
        assertThat(run(token("refresh"), chain)).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("종류 클레임이 없는 옛 토큰도 401 — 화면이 리프레시로 새 토큰을 받는다")
    void legacyRejected() {
        GatewayFilterChain chain = chain();
        assertThat(run(token(null), chain)).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }
}
