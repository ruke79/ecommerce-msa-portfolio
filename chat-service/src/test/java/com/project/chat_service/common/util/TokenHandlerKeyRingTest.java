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

/** chat 도 user-service 와 같은 키 링으로 검증한다 — 전환 뒤 새 토큰(kid=k2)으로 STOMP 에 붙고, 옛 토큰도 이어진다(docs/68 §17). */
@DisplayName("chat 토큰 검증 — 키 링")
class TokenHandlerKeyRingTest {

    private static final String LEGACY = "test-secret-test-secret-test-secret-0123";
    private static final String K2 = "k2-secret-k2-secret-k2-secret-k2-secret-0";

    private static TokenHandler handler(String keys, String active, boolean acceptLegacy) {
        TokenHandler h = new TokenHandler();
        ReflectionTestUtils.setField(h, "secretKey", LEGACY);
        ReflectionTestUtils.setField(h, "jwtSigningKeys", keys);
        ReflectionTestUtils.setField(h, "jwtActiveKid", active);
        ReflectionTestUtils.setField(h, "jwtAcceptLegacy", acceptLegacy);
        return h;
    }

    private static String token(String kid, String secret) {
        var b = JWT.create().withSubject("a@example.com").withClaim("uid", 7L).withClaim("tokenType", "access")
                .withExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        if (kid != null) b.withKeyId(kid);
        return b.sign(Algorithm.HMAC256(secret.getBytes()));
    }

    @Test
    @DisplayName("🔴 전환 뒤 — k2 토큰 · legacy 토큰 · kid 없는 옛 토큰 모두 통과")
    void afterActivate() {
        TokenHandler h = handler("k2=" + K2, "k2", true);
        assertThat(h.verifyToken(token("k2", K2))).isTrue();
        assertThat(h.verifyToken(token("legacy", LEGACY))).isTrue();
        assertThat(h.verifyToken(token(null, LEGACY))).isTrue();
    }

    @Test
    @DisplayName("🔴 legacy 은퇴 · 모르는 kid · kid 와 다른 키로 서명 — 거절")
    void rejected() {
        TokenHandler h = handler("k2=" + K2, "k2", false);
        assertThat(h.verifyToken(token(null, LEGACY))).isFalse();
        assertThat(h.verifyToken(token("legacy", LEGACY))).isFalse();
        assertThat(h.verifyToken(token("k9", K2))).isFalse();
        assertThat(h.verifyToken(token("k2", LEGACY))).isFalse();
    }
}
