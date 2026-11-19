package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.password.LoginPasswordAdvisor;
import com.project.userservice.security.risk.LoginRiskGuard;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.EmailService;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 로그인 응답의 비밀번호 권고(docs/57 §22) — 맞은 비밀번호만 보고, 응답에 {@code passwordAdvice} 를 싣고, 로그인은 그대로 된다.
 * [JA] ログイン応答のパスワード勧告 — 正しいパスワードだけを見て、応答に passwordAdvice を載せ、ログインはそのまま成功する。
 */
@DisplayName("로그인 비밀번호 권고 — 로그인 필터")
class LoginPasswordAdviceFilterTest {

    private AuthenticationManager manager;
    private LoginPasswordAdvisor advisor;
    private LoginRiskGuard guard;
    private AuthLoginFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.generateToken(anyLong(), anyString(), anyString(), anyBoolean())).thenReturn("access-token");
        when(jwtUtils.createCookie(anyString(), any(), any(java.time.Duration.class)))
                .thenAnswer(inv -> new Cookie(inv.getArgument(0), inv.getArgument(1)));
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        var rt = mock(com.project.userservice.model.redis.RefreshToken.class);
        when(rt.getToken()).thenReturn("refresh-token");
        when(refresh.createRefreshToken(42L)).thenReturn(rt);
        manager = mock(AuthenticationManager.class);
        guard = mock(LoginRiskGuard.class);
        when(guard.signals(any(), any())).thenReturn(List.of());
        advisor = mock(LoginPasswordAdvisor.class);
        filter = new AuthLoginFilter(manager, jwtUtils, refresh, mock(NotificationService.class),
                mock(DeviceRecognitionService.class), mock(SecurityAlertProducer.class), mock(RedisSessionManager.class),
                mock(PendingTwoFactor.class), guard, mock(EmailService.class));
        filter.setPasswordAdvisor(advisor);
    }

    private static Authentication auth() {
        UserDetailsImpl user = new UserDetailsImpl(42L, "kim", "kim@example.com", "pw", false, true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    private static MockHttpServletRequest login(String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/public/signin");
        request.setContent(("{\"email\":\"kim@example.com\",\"password\":\"" + password + "\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String signIn(String password) throws Exception {
        MockHttpServletRequest request = login(password);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication a = filter.attemptAuthentication(request, response);
        filter.successfulAuthentication(request, response, null, a);
        assertThat(response.getCookie("REFRESH")).as("권고가 있어도 로그인은 된다").isNotNull();
        return response.getContentAsString();
    }

    @Test
    @DisplayName("🔴 유출 비밀번호로 로그인하면 응답에 passwordAdvice=breached — 로그인은 그대로")
    void breachedPasswordIsAdvised() throws Exception {
        when(manager.authenticate(any())).thenReturn(auth());
        when(advisor.adviceFor(42L, "leaked-pw")).thenReturn(LoginPasswordAdvisor.ADVICE_BREACHED);
        assertThat(signIn("leaked-pw")).contains("\"passwordAdvice\":\"breached\"");
    }

    @Test
    @DisplayName("괜찮은 비밀번호면 응답에 그 칸이 없다")
    void okPasswordHasNoAdvice() throws Exception {
        when(manager.authenticate(any())).thenReturn(auth());
        assertThat(signIn("fine-pw")).doesNotContain("passwordAdvice");
        verify(advisor).adviceFor(eq(42L), eq("fine-pw"));
    }

    @Test
    @DisplayName("틀린 비밀번호로는 확인하지 않는다 — 외부 API 를 남의 추측에 쓰지 않는다")
    void wrongPasswordIsNotChecked() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("x"));
        assertThatThrownBy(() -> filter.attemptAuthentication(login("guess"), new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(advisor);
    }
}
