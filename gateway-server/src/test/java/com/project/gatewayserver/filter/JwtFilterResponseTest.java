package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * JWT 필터 — 토큰은 요청마다 한 번만 검증하고, 401 은 요청 언어의 문구로 돌려준다.
 *
 * <p>🐛 예전: 같은 토큰을 요청마다 여섯 번 HMAC 검증했고(만료 · 상태 · 종류 · subject · role · uid), 401 본문이 영어 원문
 * ("access token expired")이거나 한국어였으며, 만료처럼 정상인 흐름도 ERROR 로 두 번씩 찍었다.
 *
 * <p>[JA] JWT フィルター — トークンはリクエストごとに一回だけ検証し、401 はリクエスト言語の文言で返す。
 */
@DisplayName("게이트웨이 JWT — 한 번 검증 · 401 문구")
class JwtFilterResponseTest {

    private static final String SECRET = "gateway-test-secret-gateway-test-secret-01";

    private static String token(long ttlMillis, Object uid) {
        return Jwts.builder().subject("a@example.com").claim("uid", uid).claim("role", "ROLE_USER").claim("tokenType", "access")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000)).expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    private static JwtAuthorizationGatewayFilterFactory factory() {
        JwtAuthorizationGatewayFilterFactory f = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(f, "secretKey", SECRET);
        return f;
    }

    private static MockServerWebExchange run(JwtAuthorizationGatewayFilterFactory factory, String token, String lang) {
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/order-service/api/order/list")
                .header("Authorization", "Bearer " + token);
        if (lang != null) {
            req.header("Accept-Language", lang);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        filter.filter(exchange, e -> Mono.empty()).block();
        return exchange;
    }

    @Test
    @DisplayName("🔴 통과하는 요청도 토큰 검증은 한 번 — 키 링을 한 번만 찾는다")
    void verifiesOnce() {
        JwtAuthorizationGatewayFilterFactory factory = spy(factory());
        MockServerWebExchange exchange = run(factory, token(60_000, 7L), null);
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        verify(factory, times(1)).ring();
    }

    @Test
    @DisplayName("🔴 만료 토큰은 401 · 요청 언어(일본어)의 문구 · UTF-8 — 영어 원문이 아니다")
    void expiredIsLocalized() {
        MockServerWebExchange exchange = run(factory(), token(-60_000, 7L), "ja-JP,ja;q=0.9");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("ログイン").doesNotContain("expired");
    }

    @Test
    @DisplayName("uid 클레임이 숫자가 아니면 401 — 예외로 500 이 되지 않는다")
    void nonNumericUidIsUnauthorized() {
        MockServerWebExchange exchange = run(factory(), token(60_000, "abc"), null);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("다시 로그인");
    }
}
