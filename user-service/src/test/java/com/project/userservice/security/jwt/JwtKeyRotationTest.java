package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.common.constants.TokenStatus;
import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.security.RedisSessionManager;

import io.jsonwebtoken.Jwts;

/**
 * 🔴 JWT 서명 키를 바꿔도 아무도 로그아웃되지 않는다(docs/68 §17).
 *
 * <p>🐛 예전: 키가 하나라 바꾸는 순간 그 전의 모든 토큰이 서명 불일치 — 전원 로그아웃이었다.
 */
@DisplayName("JWT 키 교체 — 추가 · 전환 · 은퇴")
class JwtKeyRotationTest {

    private static final String LEGACY = "user-test-secret-user-test-secret-0123456";
    private static final String K2 = "k2-secret-k2-secret-k2-secret-k2-secret-01";

    private static JwtUtils jwt(String keys, String active, boolean acceptLegacy) {
        JwtUtils j = new JwtUtils(mock(RedisSessionManager.class));
        ReflectionTestUtils.setField(j, "jwtSecret", LEGACY);
        ReflectionTestUtils.setField(j, "jwtSigningKeys", keys);
        ReflectionTestUtils.setField(j, "jwtActiveKid", active);
        ReflectionTestUtils.setField(j, "jwtAcceptLegacy", acceptLegacy);
        ReflectionTestUtils.setField(j, "jwtExpirationMs", 60_000);
        ReflectionTestUtils.setField(j, "jwtRefreshExpirationMs", 120_000);
        return j;
    }

    private static User user() {
        User u = mock(User.class);
        Role r = mock(Role.class);
        when(r.getRoleName()).thenReturn(AppRole.ROLE_USER);
        when(u.getRole()).thenReturn(r);
        when(u.getEmail()).thenReturn("a@example.com");
        when(u.getUserId()).thenReturn(7L);
        return u;
    }

    /** 이 변경 전에 발급된 토큰 — 머리에 kid 가 없다. */
    private static String preRingToken() {
        return Jwts.builder().subject("a@example.com").claim("uid", 7L).claim("tokenType", "access")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(LEGACY.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    private static String kidOf(String token) {
        String header = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
        int i = header.indexOf("\"kid\":\"");
        return i < 0 ? null : header.substring(i + 7, header.indexOf('"', i + 7));
    }

    @Test
    @DisplayName("설정이 비어 있으면 kid=legacy 로 서명하고, kid 없는 옛 토큰도 받는다")
    void defaultIsLegacy() {
        JwtUtils j = jwt("", "", true);
        String access = j.generateTokenFromUser(user());
        assertThat(kidOf(access)).isEqualTo("legacy");
        assertThat(j.validateJwtToken(access)).isEqualTo(TokenStatus.VALID);
        assertThat(j.validateJwtToken(preRingToken())).isEqualTo(TokenStatus.VALID);
    }

    @Test
    @DisplayName("🔴 전환 뒤: 새 토큰은 k2 로 서명, 전환 전 토큰(legacy · kid 없음)도 그대로 통과 — 아무도 로그아웃되지 않는다")
    void activateKeepsOldTokensValid() {
        String before = jwt("", "", true).generateTokenFromUser(user());
        String refreshBefore = jwt("", "", true).generatRefreshTokenFromUser(user());

        JwtUtils after = jwt("k2=" + K2, "k2", true);
        String fresh = after.generateTokenFromUser(user());

        assertThat(kidOf(fresh)).isEqualTo("k2");
        assertThat(after.validateJwtToken(fresh)).isEqualTo(TokenStatus.VALID);
        assertThat(after.validateJwtToken(before)).isEqualTo(TokenStatus.VALID);
        assertThat(after.validateJwtToken(preRingToken())).isEqualTo(TokenStatus.VALID);
        assertThat(after.isRefreshTokenType(refreshBefore)).as("리프레시도 이어진다").isTrue();
        assertThat(after.getIdFromJwtToken(before)).isEqualTo("a@example.com");
    }

    @Test
    @DisplayName("🔴 legacy 은퇴 뒤: legacy · kid 없는 토큰은 거절, k2 토큰은 통과")
    void retiredLegacyRejected() {
        String old = jwt("", "", true).generateTokenFromUser(user());
        JwtUtils retired = jwt("k2=" + K2, "k2", false);

        assertThat(retired.validateJwtToken(old)).isEqualTo(TokenStatus.UNSUPPORTED);
        assertThat(retired.validateJwtToken(preRingToken())).isEqualTo(TokenStatus.UNSUPPORTED);
        assertThat(retired.isAccessToken(old)).isFalse();
        assertThat(retired.validateJwtToken(retired.generateTokenFromUser(user()))).isEqualTo(TokenStatus.VALID);
    }

    @Test
    @DisplayName("🔒 모르는 kid 로 서명된 토큰은 거절 — 링에 없는 키를 지어내 붙여도 통하지 않는다")
    void unknownKidRejected() {
        String forged = Jwts.builder().header().keyId("k9").and()
                .subject("a@example.com").claim("tokenType", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(K2.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
        assertThat(jwt("k2=" + K2, "k2", true).validateJwtToken(forged)).isEqualTo(TokenStatus.UNSUPPORTED);
    }

    @Test
    @DisplayName("🔒 kid 를 바꿔 붙여도 서명은 그 kid 의 키로 확인한다 — legacy 키로 서명하고 kid=k2 라 적은 토큰은 거절")
    void kidMustMatchSigningKey() {
        String mislabeled = Jwts.builder().header().keyId("k2").and()
                .subject("a@example.com").claim("tokenType", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(LEGACY.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
        // 서명 불일치는 예전부터 예외(SignatureException)로 올라간다 — 호출하는 필터가 거절로 다룬다. 통과만 하지 않으면 된다.
        JwtUtils j = jwt("k2=" + K2, "k2", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> j.validateJwtToken(mislabeled))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
