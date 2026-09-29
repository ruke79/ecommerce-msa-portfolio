package com.project.chat_service.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

/**
 * 🔒 액세스 토큰만 받는다 — 같은 키로 서명된 리프레시 토큰(14일)으로 STOMP·API 에 붙지 못하게(user-service JwtUtils.TOKEN_TYPE_CLAIM).
 */
@DisplayName("chat 토큰 검증 — 종류")
class TokenHandlerTypeTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123";

    private static TokenHandler handler() {
        TokenHandler h = new TokenHandler();
        ReflectionTestUtils.setField(h, "secretKey", SECRET);
        return h;
    }

    private static String token(String type) {
        var b = JWT.create().withSubject("a@example.com").withClaim("uid", 7L)
                .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        if (type != null) b.withClaim("tokenType", type);
        return b.sign(Algorithm.HMAC256(SECRET.getBytes()));
    }

    @Test
    @DisplayName("액세스 토큰은 통과")
    void access() {
        assertThat(handler().verifyToken(token("access"))).isTrue();
    }

    @Test
    @DisplayName("🔴 리프레시 토큰·종류 없는 옛 토큰은 거절")
    void refreshAndLegacyRejected() {
        assertThat(handler().verifyToken(token("refresh"))).isFalse();
        assertThat(handler().verifyToken(token(null))).isFalse();
    }
}
