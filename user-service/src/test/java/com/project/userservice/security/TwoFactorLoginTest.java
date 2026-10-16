package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;

/**
 * 🔴 2단계 인증은 서버가 강제한다 (docs/54 §1.3).
 *
 * <p>예전에는 비밀번호 로그인이 2FA 계정에도 액세스 토큰과 REFRESH 쿠키를 먼저 줬고(코드 입력을 기다린 것은
 * 프론트뿐), 소셜 경로의 대기 쿠키는 액세스 토큰과 같은 키로 서명한 보통 JWT 라 게이트웨이를 통과했다.
 */
@DisplayName("2단계 인증 — 서버 강제")
class TwoFactorLoginTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";

    private final Map<String, String> store = new HashMap<>();
    private PendingTwoFactor pending;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(ops.increment(anyString())).thenAnswer(inv -> {
            long n = Long.parseLong(store.getOrDefault(inv.getArgument(0), "0")) + 1;
            store.put(inv.getArgument(0), String.valueOf(n));
            return n;
        });
        org.mockito.Mockito.doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        pending = new PendingTwoFactor(SECRET, redis);
    }

    // ── 대기 토큰 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 대기 토큰은 액세스 토큰 키로 검증되지 않는다 — 게이트웨이·다른 서비스가 받아 주지 않는다")
    void pendingTokenIsNotAnAccessToken() {
        String token = pending.issue(42L);

        // 게이트웨이(JwtAuthorizationGatewayFilterFactory)와 같은 방식의 검증
        var accessKey = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token))
                .isInstanceOf(JwtException.class);
        assertThat(pending.userIdOf(token)).contains(42L);
    }

    @Test
    @DisplayName("액세스 토큰을 대기 토큰으로 내밀어도 받지 않는다")
    void accessTokenIsNotAPendingToken() {
        var accessKey = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String access = Jwts.builder().subject("42").id("j").claim("purpose", "2fa_pending")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(accessKey).compact();

        assertThat(pending.userIdOf(access)).isEmpty();
    }

    @Test
    @DisplayName("🔴 틀린 코드는 토큰당 5번까지 — 넘으면 그 토큰은 탄다")
    void attemptsAreLimited() {
        String token = pending.issue(42L);
        for (int i = 1; i < PendingTwoFactor.MAX_ATTEMPTS; i++) {
            assertThat(pending.recordFailure(token)).isTrue();
        }
        assertThat(pending.recordFailure(token)).isFalse();
        assertThat(pending.userIdOf(token)).isEmpty();
    }

    @Test
    @DisplayName("통과한 토큰은 다시 쓸 수 없다")
    void consumedTokenIsDead() {
        String token = pending.issue(42L);
        pending.consume(token);

        assertThat(pending.userIdOf(token)).isEmpty();
    }

    @Test
    @DisplayName("쓰레기·빈 값은 조용히 거절한다")
    void garbageIsRejected() {
        assertThat(pending.userIdOf(null)).isEmpty();
        assertThat(pending.userIdOf("not.a.jwt")).isEmpty();
        assertThat(pending.recordFailure("not.a.jwt")).isFalse();
    }

    // ── 로그인 필터 ───────────────────────────────────────────────────────

    private AuthLoginFilter filter(JwtUtils jwtUtils, RefreshTokenService refresh, RedisSessionManager sessions) {
        return new AuthLoginFilter(mock(AuthenticationManager.class), jwtUtils, refresh,
                mock(NotificationService.class), mock(DeviceRecognitionService.class),
                mock(SecurityAlertProducer.class), sessions, pending,
                mock(com.project.userservice.security.risk.LoginRiskGuard.class), mock(com.project.userservice.service.EmailService.class));
    }

    private static UsernamePasswordAuthenticationToken auth(boolean twoFactor) {
        UserDetailsImpl user = new UserDetailsImpl(42L, "kim", "kim@example.com", "pw", twoFactor, true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    @Test
    @DisplayName("🔴 2FA 계정은 비밀번호가 맞아도 액세스 토큰·REFRESH 를 받지 못한다 — 대기 쿠키만")
    void passwordLoginWithTwoFactorGetsOnlyPendingCookie() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.createCookie(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(inv -> new Cookie(inv.getArgument(0), inv.getArgument(1)));
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        RedisSessionManager sessions = mock(RedisSessionManager.class);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(jwtUtils, refresh, sessions)
                .successfulAuthentication(new MockHttpServletRequest(), response, null, auth(true));

        assertThat(response.getHeader("access")).isNull();
        assertThat(response.getCookie("REFRESH")).isNull();
        assertThat(response.getCookie(PendingTwoFactor.COOKIE)).isNotNull();
        assertThat(pending.userIdOf(response.getCookie(PendingTwoFactor.COOKIE).getValue())).contains(42L);
        assertThat(response.getContentAsString()).contains(AuthLoginFilter.TWO_FACTOR_REQUIRED_CODE);
        verify(jwtUtils, never()).generateToken(anyLong(), anyString(), anyString(), anyBoolean());
        verify(refresh, never()).createRefreshToken(anyLong());
        // 비밀번호만으로 남의 기존 세션을 끊지 못한다
        verify(sessions, never()).getSession(anyString());
    }

    @Test
    @DisplayName("2FA 가 없는 계정은 예전처럼 토큰을 받는다")
    void passwordLoginWithoutTwoFactorIsUnchanged() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.generateToken(anyLong(), anyString(), anyString(), anyBoolean())).thenReturn("access-token");
        when(jwtUtils.createCookie(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(inv -> new Cookie(inv.getArgument(0), inv.getArgument(1)));
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        var rt = mock(com.project.userservice.model.redis.RefreshToken.class);
        when(rt.getToken()).thenReturn("refresh-token");
        when(refresh.createRefreshToken(42L)).thenReturn(rt);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(jwtUtils, refresh, mock(RedisSessionManager.class))
                .successfulAuthentication(new MockHttpServletRequest(), response, null, auth(false));

        assertThat(response.getHeader("access")).isEqualTo("access-token");
        assertThat(response.getCookie("REFRESH")).isNotNull();
        assertThat(response.getCookie(PendingTwoFactor.COOKIE)).isNull();
    }

    /** 🐛 예전: 다중 로그인 알림 행(users.notification)과 Redis 메시지에 실제 이메일을 실었다 — 탈퇴해도 지워지지 않았다(§6). */
    @Test
    @DisplayName("🔴 다중 로그인으로 이전 세션을 끊는 알림에는 userId 만 — 이메일이 아니다")
    void multiLoginNotificationCarriesUserIdNotEmail() throws Exception {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.generateToken(anyLong(), anyString(), anyString(), anyBoolean())).thenReturn("access-token");
        when(jwtUtils.createCookie(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(inv -> new Cookie(inv.getArgument(0), inv.getArgument(1)));
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        var rt = mock(com.project.userservice.model.redis.RefreshToken.class);
        when(rt.getToken()).thenReturn("refresh-token");
        when(refresh.createRefreshToken(42L)).thenReturn(rt);
        RedisSessionManager sessions = mock(RedisSessionManager.class);
        when(sessions.getSession("kim@example.com")).thenReturn("old-session");
        NotificationService notifications = mock(NotificationService.class);

        new AuthLoginFilter(mock(AuthenticationManager.class), jwtUtils, refresh, notifications,
                mock(DeviceRecognitionService.class), mock(SecurityAlertProducer.class), sessions, pending,
                mock(com.project.userservice.security.risk.LoginRiskGuard.class), mock(com.project.userservice.service.EmailService.class))
                .successfulAuthentication(new MockHttpServletRequest(), new MockHttpServletResponse(), null, auth(false));

        verify(notifications).sendNotification("42", "old-session", "Logout");
        verify(notifications, never()).sendNotification(org.mockito.ArgumentMatchers.eq("kim@example.com"), anyString(), anyString());
    }
}
