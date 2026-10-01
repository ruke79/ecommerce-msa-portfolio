package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.security.RedisSessionManager;

import io.jsonwebtoken.ExpiredJwtException;

/**
 * 🔴 로그인의 절대 수명(30일) — 리프레시 토큰은 갱신마다 14일씩 늘어나서, 이것이 없으면 가끔 쓰기만 해도 로그인이 끝나지 않았다(docs/68 §12).
 */
// [JA] ログインの絶対寿命(30 日) — リフレッシュトークンは更新のたびに 14 日延びるので、これがないと時々使うだけでログインが終わらなかった。
@DisplayName("리프레시 토큰 — 절대 수명 30일")
class AbsoluteLifetimeTest {

    private static final String SECRET = "user-test-secret-user-test-secret-0123456";
    private static final long DAY = 24L * 3600 * 1000;

    private static JwtUtils jwt() {
        JwtUtils j = new JwtUtils(mock(RedisSessionManager.class));
        ReflectionTestUtils.setField(j, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(j, "jwtExpirationMs", 60_000);
        ReflectionTestUtils.setField(j, "jwtRefreshExpirationMs", (int) (14 * DAY));
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

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    @Test
    @DisplayName("새 로그인 — 최초 로그인 시각은 지금, 만료는 14일 뒤")
    void freshLogin() {
        JwtUtils j = jwt();
        String t = j.generatRefreshTokenFromUser(user());

        assertThat(j.getAuthTimeFromJwtToken(t)).isBetween(nowSec() - 2, nowSec());
        assertThat(j.remainingLifetime(t)).isBetween(Duration.ofDays(14).minusSeconds(5), Duration.ofDays(14));
    }

    @Test
    @DisplayName("🔴 갱신은 최초 로그인 시각을 이어받고, 만료는 '최초 로그인 + 30일' 을 넘지 않는다")
    void rotationIsCappedByAbsoluteLifetime() {
        JwtUtils j = jwt();
        long loggedIn = nowSec() - 25 * 24 * 3600;   // 25일 전에 로그인

        String t = j.generateRefreshToken(user(), "fam-1", loggedIn);

        assertThat(j.getAuthTimeFromJwtToken(t)).isEqualTo(loggedIn);
        // 14일이 아니라 남은 5일
        assertThat(j.remainingLifetime(t)).isBetween(Duration.ofDays(5).minusSeconds(5), Duration.ofDays(5));
    }

    @Test
    @DisplayName("🔴 최초 로그인부터 30일이 지나면 새로 만든 토큰도 이미 만료다 — 갱신으로 연장할 수 없다")
    void pastAbsoluteLifetimeCannotBeExtended() {
        JwtUtils j = jwt();
        long loggedIn = nowSec() - 31 * 24 * 3600;

        assertThat(j.isPastAbsoluteLifetime(loggedIn)).isTrue();
        String t = j.generateRefreshToken(user(), "fam-1", loggedIn);
        assertThatThrownBy(() -> j.getFamilyIdFromJwtToken(t)).isInstanceOf(ExpiredJwtException.class);
        assertThat(j.remainingLifetime(t)).isZero();
    }

    @Test
    @DisplayName("29일째는 아직 — 경계 바로 앞은 살아 있다")
    void justBeforeTheLimit() {
        assertThat(jwt().isPastAbsoluteLifetime(nowSec() - 29 * 24 * 3600)).isFalse();
    }
}
