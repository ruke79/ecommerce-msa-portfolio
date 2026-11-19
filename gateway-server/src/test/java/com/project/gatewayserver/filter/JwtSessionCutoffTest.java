package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * 로그인 해제 뒤의 액세스 토큰은 HTTP 에서도 바로 401(docs/57 §22 · docs/68 §7). 🐛 예전에는 서명 · 만료만 봐서 훔친 토큰이
 * "모든 기기에서 로그아웃" · 비밀번호 변경 뒤에도 최대 15분 통했다.
 * [JA] ログイン解除後のアクセストークンは HTTP でもすぐ 401。以前は盗まれたトークンが最大 15 分通った。
 */
@DisplayName("게이트웨이 JWT — 로그인 해제 시각(auth:cutoff)")
class JwtSessionCutoffTest {

    private static final String SECRET = "gateway-test-secret-gateway-test-secret-01";

    private static String accessIssuedAt(long epochSec) {
        return Jwts.builder().subject("a@example.com").claim("uid", 7L).claim("role", "ROLE_USER").claim("tokenType", "access")
                .issuedAt(new Date(epochSec * 1000)).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    @SuppressWarnings("unchecked")
    private static ReactiveStringRedisTemplate redisReturning(Mono<String> value) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(any())).thenAnswer(inv -> "auth:cutoff:7".equals(inv.getArgument(0)) ? value : Mono.empty());
        return redis;
    }

    /** @return 아래로 보냈으면 null, 아니면 응답 상태 */
    private static HttpStatus run(String token, ReactiveStringRedisTemplate redis) {
        JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(factory, "secretKey", SECRET);
        ReflectionTestUtils.setField(factory, "redis", redis);
        ReflectionTestUtils.setField(factory, "sessionCutoffTimeout", Duration.ofMillis(200));
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/order-service/api/order/list").header("Authorization", "Bearer " + token));
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return passed.get() ? null : (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static final long NOW = System.currentTimeMillis() / 1000;

    @Test
    @DisplayName("🔴 해제 시각보다 먼저 발급된 토큰은 401")
    void tokenIssuedBeforeCutoffIsRejected() {
        assertThat(run(accessIssuedAt(NOW - 120), redisReturning(Mono.just(String.valueOf(NOW - 60)))))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("해제 뒤에 받은 토큰(이 기기의 재발급 · 갱신)은 통과")
    void tokenIssuedAfterCutoffPasses() {
        assertThat(run(accessIssuedAt(NOW - 10), redisReturning(Mono.just(String.valueOf(NOW - 60))))).isNull();
    }

    @Test
    @DisplayName("해제 기록이 없으면 통과 — 대부분의 요청")
    void noCutoffPasses() {
        assertThat(run(accessIssuedAt(NOW - 120), redisReturning(Mono.empty()))).isNull();
    }

    @Test
    @DisplayName("Redis 가 실패하거나 느리면 막지 않는다 — 전체 장애로 키우지 않는다(만료가 15분 상한)")
    void redisTroubleFailsOpen() {
        assertThat(run(accessIssuedAt(NOW - 120), redisReturning(Mono.error(new IllegalStateException("down"))))).isNull();
        assertThat(run(accessIssuedAt(NOW - 120), redisReturning(Mono.never()))).isNull();
    }
}
