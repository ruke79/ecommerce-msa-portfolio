package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.risk.LoginRiskGuard;
import com.project.userservice.security.risk.LoginRiskGuard.Signal;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.EmailService;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 위험 기반 로그인 — 비밀번호가 맞아도 처음 보는 기기 + 공격 신호면 토큰 대신 메일 확인(docs/68 §19).
 */
@DisplayName("위험 기반 로그인 — 로그인 필터")
class LoginRiskFilterTest {

    private JwtUtils jwtUtils;
    private RefreshTokenService refresh;
    private DeviceRecognitionService devices;
    private LoginRiskGuard guard;
    private EmailService email;
    private AuthLoginFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.generateToken(anyLong(), anyString(), anyString(), anyBoolean())).thenReturn("access-token");
        when(jwtUtils.createCookie(anyString(), any(), any(java.time.Duration.class)))
                .thenAnswer(inv -> new Cookie(inv.getArgument(0), inv.getArgument(1)));
        refresh = mock(RefreshTokenService.class);
        var rt = mock(com.project.userservice.model.redis.RefreshToken.class);
        when(rt.getToken()).thenReturn("refresh-token");
        when(refresh.createRefreshToken(42L)).thenReturn(rt);
        devices = mock(DeviceRecognitionService.class);
        guard = mock(LoginRiskGuard.class);
        email = mock(EmailService.class);
        filter = new AuthLoginFilter(mock(AuthenticationManager.class), jwtUtils, refresh,
                mock(NotificationService.class), devices, mock(SecurityAlertProducer.class),
                mock(RedisSessionManager.class), mock(PendingTwoFactor.class), guard, email);
    }

    private static UsernamePasswordAuthenticationToken auth() {
        UserDetailsImpl user = new UserDetailsImpl(42L, "kim", "kim@example.com", "pw", false, true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private void risky() {
        when(devices.isKnown(eq(42L), any())).thenReturn(false);
        when(guard.signals(eq("kim@example.com"), any())).thenReturn(List.of(Signal.IP_FANOUT));
        when(guard.issue(eq(42L), any())).thenReturn(new LoginRiskGuard.IssuedChallenge(
                new LoginRiskGuard.Issued("pending-value", List.of(Signal.IP_FANOUT)), "123456"));
    }

    @Test
    @DisplayName("🔴 처음 보는 기기 + 신호 — 토큰 · REFRESH 없이 확인 쿠키만, 코드는 메일로만, 기기는 아직 기록하지 않는다")
    void riskyLoginIsChallenged() throws Exception {
        risky();
        when(email.sendLoginChallengeEmail(42L, "kim@example.com", "123456")).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.successfulAuthentication(new MockHttpServletRequest(), response, null, auth());

        assertThat(response.getHeader("access")).isNull();
        assertThat(response.getCookie("REFRESH")).isNull();
        assertThat(response.getCookie(LoginRiskGuard.COOKIE).getValue()).isEqualTo("pending-value");
        assertThat(response.getContentAsString()).contains(AuthLoginFilter.LOGIN_CHECK_REQUIRED_CODE)
                .doesNotContain("123456").doesNotContain("pending-value");
        verify(email).sendLoginChallengeEmail(42L, "kim@example.com", "123456");
        verify(refresh, never()).createRefreshToken(anyLong());
        verify(devices, never()).recognize(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 확인 메일을 보낼 수 없으면 통과시키지 않는다 — 503, 토큰 없음")
    void mailFailureDoesNotLetIn() throws Exception {
        risky();
        when(email.sendLoginChallengeEmail(anyLong(), anyString(), anyString())).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.successfulAuthentication(new MockHttpServletRequest(), response, null, auth());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getCookie("REFRESH")).isNull();
        assertThat(response.getCookie(LoginRiskGuard.COOKIE)).isNull();
    }

    @Test
    @DisplayName("아는 기기는 신호를 보지 않고 예전처럼 들어온다")
    void knownDevicePasses() throws Exception {
        when(devices.isKnown(eq(42L), any())).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.successfulAuthentication(new MockHttpServletRequest(), response, null, auth());

        assertThat(response.getHeader("access")).isEqualTo("access-token");
        assertThat(response.getCookie("REFRESH")).isNotNull();
        verify(guard, never()).signals(any(), any());
    }

    @Test
    @DisplayName("처음 보는 기기라도 신호가 없으면 들어온다 · 판단이 실패해도(Redis 장애) 막지 않는다")
    void noSignalOrFailurePasses() throws Exception {
        when(devices.isKnown(eq(42L), any())).thenReturn(false);
        when(guard.signals(any(), any())).thenReturn(List.of());
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.successfulAuthentication(new MockHttpServletRequest(), ok, null, auth());
        assertThat(ok.getCookie("REFRESH")).isNotNull();

        when(guard.signals(any(), any())).thenThrow(new IllegalStateException("redis down"));
        MockHttpServletResponse failed = new MockHttpServletResponse();
        filter.successfulAuthentication(new MockHttpServletRequest(), failed, null, auth());
        assertThat(failed.getCookie("REFRESH")).isNotNull();
    }

    @Test
    @DisplayName("🔴 비밀번호가 틀린 시도만 신호로 기록한다 — 잠긴 계정은 세지 않는다")
    void failuresRecorded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute(AuthLoginFilter.EMAIL_ATTR, "kim@example.com");

        filter.unsuccessfulAuthentication(request, new MockHttpServletResponse(), new BadCredentialsException("x"));
        verify(guard).recordFailure(eq("kim@example.com"), any());

        LoginRiskGuard other = mock(LoginRiskGuard.class);
        AuthLoginFilter f2 = new AuthLoginFilter(mock(AuthenticationManager.class), jwtUtils, refresh,
                mock(NotificationService.class), devices, mock(SecurityAlertProducer.class),
                mock(RedisSessionManager.class), mock(PendingTwoFactor.class), other, email);
        f2.unsuccessfulAuthentication(request, new MockHttpServletResponse(), new LockedException("locked"));
        verify(other, never()).recordFailure(any(), any());
    }

    /** 🐛 예전: 위험 판정이 X-Forwarded-For 의 첫 항목을 썼다 — 요청마다 난수 IP 를 붙이면 IP_FANOUT 이 켜지지 않았다. */
    @Test
    @DisplayName("🔴 실패 기록의 IP 는 클라이언트가 고를 수 없는 값 — X-Forwarded-For 가 아니라 CF-Connecting-IP(없으면 소켓)")
    void riskUsesTrustedIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.42.0.9");
        request.addHeader("X-Forwarded-For", "198.51.100.77, 10.42.0.1");
        request.setAttribute(AuthLoginFilter.EMAIL_ATTR, "kim@example.com");

        filter.unsuccessfulAuthentication(request, new MockHttpServletResponse(), new BadCredentialsException("x"));
        verify(guard).recordFailure("kim@example.com", "10.42.0.9");

        request.addHeader("CF-Connecting-IP", "203.0.113.50");
        filter.unsuccessfulAuthentication(request, new MockHttpServletResponse(), new BadCredentialsException("x"));
        verify(guard).recordFailure("kim@example.com", "203.0.113.50");
    }
}
