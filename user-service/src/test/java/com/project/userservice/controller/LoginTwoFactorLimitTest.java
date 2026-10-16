package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.PendingTwoFactor;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;
import com.project.userservice.service.UserService.TwoFactorChange;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 로그인 2단계의 사용자별 시도 한도(docs/68 §15).
 *
 * <p>🐛 예전: 대기 토큰당 5번만 셌다 — 비밀번호로 다시 로그인하면 새 토큰 = 다시 5번이었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("로그인 2단계 — 사용자별 시도 한도")
class LoginTwoFactorLimitTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private PendingTwoFactor pendingTwoFactor;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private AuthController controller;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().build();
        user.setUserId(7L);
        user.setTwoFactorEnabled(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(pendingTwoFactor.userIdOf("pending")).thenReturn(Optional.of(7L));
        when(pendingTwoFactor.recordFailure("pending")).thenReturn(true);
        when(jwtUtils.createCookie(any(), any(), any())).thenAnswer(inv -> new Cookie(inv.getArgument(0), "x"));
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "7", "refresh"));

        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(PendingTwoFactor.COOKIE, "pending"));
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("🔴 사용자 한도에 걸리면 429 — 대기 토큰을 태우고 세션을 주지 않는다")
    void lockedUserGets429() {
        when(userService.verifyLoginTwoFactor(eq(7L), anyInt())).thenReturn(TwoFactorChange.TOO_MANY_ATTEMPTS);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor(123456, request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(((Map<?, ?>) r.getBody()).get("message").toString()).contains("너무 많이");
        verify(pendingTwoFactor).consume("pending");
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("틀린 코드는 401 — 토큰당 기록도 그대로 남긴다")
    void wrongCodeIs401() {
        when(userService.verifyLoginTwoFactor(eq(7L), anyInt())).thenReturn(TwoFactorChange.INVALID_CODE);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor(111111, request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(pendingTwoFactor).recordFailure("pending");
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("맞으면 세션 쿠키를 준다 — 사용자별 확인을 지난다")
    void rightCodeIssuesSession() {
        when(userService.verifyLoginTwoFactor(eq(7L), anyInt())).thenReturn(TwoFactorChange.OK);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor(123456, request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(userService).verifyLoginTwoFactor(7L, 123456);
        verify(userService, never()).validate2FACode(anyLong(), anyInt());
        verify(refreshTokenService).createRefreshToken(7L);
    }

    @Test
    @DisplayName("🔴 코드를 받는 사이 관리자가 잠근 계정 — 맞는 코드여도 세션을 주지 않는다")
    void restrictedMeanwhileGetsNoSession() {
        user.setAccountNonLocked(false);
        when(userService.verifyLoginTwoFactor(eq(7L), anyInt())).thenReturn(TwoFactorChange.OK);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor(123456, request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }
}
