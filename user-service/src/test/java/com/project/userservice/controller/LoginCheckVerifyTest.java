package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
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

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.DeviceIdentity;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.risk.LoginRiskGuard;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.DeviceRecognitionService.Recognition;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 위험 기반 로그인의 메일 확인 엔드포인트(docs/68 §19).
 *
 * <p>통과한 브라우저만 세션 · "아는 기기" 가 된다. 틀림 · 만료 · 막힌 계정은 세션을 주지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("로그인 메일 확인 — /public/verify-login-check")
class LoginCheckVerifyTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private LoginRiskGuard loginRiskGuard;
    @Mock private DeviceRecognitionService deviceRecognitionService;
    @Mock private SecurityAlertProducer securityAlertProducer;

    @InjectMocks private AuthController controller;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("a@example.com");
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        // 쿠키는 이름과 수명만 본다 — 수명 0 이면 지우는 쿠키다.
        when(jwtUtils.createCookie(any(), any(), any())).thenAnswer(inv -> {
            Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1) == null ? "" : inv.getArgument(1));
            c.setMaxAge((int) ((Duration) inv.getArgument(2)).getSeconds());
            return c;
        });
        when(jwtUtils.refreshTokenLifetime()).thenReturn(Duration.ofDays(1));
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "7", "refresh"));
        when(deviceRecognitionService.recognize(eq(7L), any(), any(), any()))
                .thenReturn(new Recognition(null, "device-token", true));

        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(LoginRiskGuard.COOKIE, "pending"));
        response = new MockHttpServletResponse();
    }

    private Cookie cookie(String name) {
        return response.getCookie(name);
    }

    @Test
    @DisplayName("맞는 코드 — 기기를 기록하고 새 기기를 알리고 REFRESH 를 주고 대기 쿠키를 지운다")
    void passedIssuesSession() {
        when(loginRiskGuard.verify("pending", "123456")).thenReturn(new LoginRiskGuard.Passed(7L));

        ResponseEntity<?> r = controller.verifyLoginCheck("123456", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(refreshTokenService).createRefreshToken(7L);
        verify(deviceRecognitionService).recognize(eq(7L), any(), any(), any());
        verify(securityAlertProducer).publishNewDevice(eq(7L), eq("a@example.com"), any());
        assertThat(cookie("REFRESH")).isNotNull();
        assertThat(cookie(DeviceIdentity.COOKIE_NAME).getValue()).isEqualTo("device-token");
        assertThat(cookie(LoginRiskGuard.COOKIE).getMaxAge()).isZero();
    }

    @Test
    @DisplayName("🔴 틀린 코드(남은 시도 있음) — 401, 세션 없음, 대기 쿠키는 남긴다")
    void wrongWithAttemptsLeft() {
        when(loginRiskGuard.verify("pending", "000000")).thenReturn(new LoginRiskGuard.Wrong(true));

        ResponseEntity<?> r = controller.verifyLoginCheck("000000", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(((Map<?, ?>) r.getBody()).get("message").toString()).contains("올바르지 않습니다");
        assertThat(cookie(LoginRiskGuard.COOKIE)).isNull();
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
        verify(deviceRecognitionService, never()).recognize(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 시도를 다 썼다 — 401 만료 안내, 대기 쿠키를 지운다")
    void wrongNoMoreAttempts() {
        when(loginRiskGuard.verify("pending", "000000")).thenReturn(new LoginRiskGuard.Wrong(false));

        ResponseEntity<?> r = controller.verifyLoginCheck("000000", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(((Map<?, ?>) r.getBody()).get("message").toString()).contains("다시 시작");
        assertThat(cookie(LoginRiskGuard.COOKIE).getMaxAge()).isZero();
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("🔴 대기 값이 없거나 만료 — 401, 세션 없음")
    void expired() {
        request.setCookies();
        when(loginRiskGuard.verify(null, "123456")).thenReturn(new LoginRiskGuard.Expired());

        ResponseEntity<?> r = controller.verifyLoginCheck("123456", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("🔴 코드를 받는 사이 정지된 계정 — 맞는 코드여도 세션을 주지 않는다")
    void disabledUserGetsNoSession() {
        user.setEnabled(false);
        when(loginRiskGuard.verify("pending", "123456")).thenReturn(new LoginRiskGuard.Passed(7L));

        ResponseEntity<?> r = controller.verifyLoginCheck("123456", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
        verify(deviceRecognitionService, never()).recognize(anyLong(), any(), any(), any());
    }
}
