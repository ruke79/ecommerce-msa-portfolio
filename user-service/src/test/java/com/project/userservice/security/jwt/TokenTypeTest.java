package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;

/**
 * 🔒 액세스·리프레시 토큰은 종류 클레임으로 구분된다 (JwtUtils.TOKEN_TYPE_CLAIM).
 *
 * <p>🐛 둘이 같은 키·같은 클레임이라 게이트웨이에서 리프레시 토큰이 액세스로 통했고, user-service 는 만료된 액세스 토큰의
 * subject 를 그대로 믿었다.
 */
@DisplayName("JWT 종류 — 액세스·리프레시")
class TokenTypeTest {

    private static final String SECRET = "user-test-secret-user-test-secret-0123456";

    private static JwtUtils jwt() {
        JwtUtils j = new JwtUtils(mock(RedisSessionManager.class));
        ReflectionTestUtils.setField(j, "jwtSecret", SECRET);
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

    @Test
    @DisplayName("액세스 토큰은 액세스, 리프레시 경로에는 못 쓴다")
    void accessToken() {
        JwtUtils j = jwt();
        String access = j.generateTokenFromUser(user());
        assertThat(j.isAccessToken(access)).isTrue();
        assertThat(j.isRefreshTokenType(access)).isFalse();
        String viaLogin = j.generateToken(7L, "a@example.com", "ROLE_USER", false);
        assertThat(j.isAccessToken(viaLogin)).isTrue();
    }

    @Test
    @DisplayName("🔴 리프레시 토큰은 액세스가 아니다")
    void refreshToken() {
        JwtUtils j = jwt();
        String refresh = j.generateRefreshTokenFromUser(user());
        assertThat(j.isAccessToken(refresh)).isFalse();
        assertThat(j.isRefreshTokenType(refresh)).isTrue();
    }

    @Test
    @DisplayName("종류 클레임이 없는 옛 토큰 — 액세스로는 거절, 리프레시 경로에서는 받는다(최대 14일 이행)")
    void legacyToken() {
        JwtUtils j = jwt();
        String legacy = Jwts.builder().subject("a@example.com").claim("uid", 7L)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
        assertThat(j.isAccessToken(legacy)).isFalse();
        assertThat(j.isRefreshTokenType(legacy)).isTrue();
    }

    @Test
    @DisplayName("🔴 만료된 토큰의 subject 를 돌려주지 않는다")
    void expiredIsNotTrusted() {
        JwtUtils j = jwt();
        String expired = Jwts.builder().subject("a@example.com").claim("tokenType", "access")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
        assertThat(j.validateJwtToken(expired)).isEqualTo(TokenStatus.EXPIRED);
        assertThat(j.isAccessToken(expired)).isFalse();
        assertThatThrownBy(() -> j.getIdFromJwtToken(expired)).isInstanceOf(ExpiredJwtException.class);
    }
}
