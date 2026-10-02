package com.project.userservice.security.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.PasskeyCredential;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.DeviceRecognitionService.Recognition;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 패스키 엔드포인트 — 등록 전 최근 로그인 · 알림 · 남의 것 404 · 로그인 끝 처리(docs/68 §20).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("패스키 — 엔드포인트")
class PasskeyControllerTest {

    @Mock private PasskeyService passkeyService;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtUtils jwtUtils;
    @Mock private DeviceRecognitionService deviceRecognitionService;
    @Mock private SecurityAlertProducer securityAlertProducer;

    @InjectMocks private PasskeyController controller;

    private User user;
    private UserDetailsImpl principal;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("a@example.com");
        user.setEnabled(true);
        principal = org.mockito.Mockito.mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtUtils.createCookie(any(), any(), any())).thenAnswer(inv -> {
            Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1) == null ? "" : inv.getArgument(1));
            c.setMaxAge((int) ((Duration) inv.getArgument(2)).getSeconds());
            return c;
        });
        when(jwtUtils.refreshTokenLifetime()).thenReturn(Duration.ofDays(1));
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "7", "refresh"));
        when(deviceRecognitionService.recognize(eq(7L), any(), any(), any()))
                .thenReturn(new Recognition(null, "device-token", true));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "reauthWindow", Duration.ofMinutes(10));
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private void loggedInSecondsAgo(long seconds) {
        when(refreshTokenService.authTimeOf("refresh-cookie", user))
                .thenReturn(OptionalLong.of(Instant.now().getEpochSecond() - seconds));
    }

    @Test
    @DisplayName("🔴 등록 옵션 — 최근 로그인이 아니면 403 reauth_required (훔친 세션으로 뒷문을 심지 못하게)")
    void registrationNeedsRecentLogin() {
        loggedInSecondsAgo(3600);
        ResponseEntity<?> stale = controller.registrationOptions(principal, "refresh-cookie");
        assertThat(stale.getStatusCode().value()).isEqualTo(403);
        assertThat(((Map<?, ?>) stale.getBody()).get("code")).isEqualTo("reauth_required");

        when(refreshTokenService.authTimeOf(null, user)).thenReturn(OptionalLong.empty());
        assertThat(controller.registrationOptions(principal, null).getStatusCode().value()).isEqualTo(403);
        verify(passkeyService, never()).registrationOptions(any());

        loggedInSecondsAgo(60);
        when(passkeyService.registrationOptions(user)).thenReturn(Map.of("challenge", "c"));
        assertThat(controller.registrationOptions(principal, "refresh-cookie").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("🔴 등록 — 저장 순간에도 최근 로그인을 보고, 성공하면 주인에게 알린다")
    void registerChecksAgainAndAlerts() throws Exception {
        var body = new ObjectMapper().readTree("{\"name\":\"폰\",\"credential\":{\"id\":\"x\"}}");
        loggedInSecondsAgo(3600);
        assertThat(controller.register(principal, "refresh-cookie", body).getStatusCode().value()).isEqualTo(403);
        verify(passkeyService, never()).register(anyLong(), anyString(), anyString());

        loggedInSecondsAgo(30);
        PasskeyCredential saved = PasskeyCredential.builder().passkeyId(3L).userId(7L).name("폰")
                .createdAt(LocalDateTime.now()).build();
        when(passkeyService.register(7L, "{\"id\":\"x\"}", "폰")).thenReturn(saved);
        ResponseEntity<?> r = controller.register(principal, "refresh-cookie", body);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        verify(securityAlertProducer).publishAccountEvent(7L, "a@example.com", SecurityAlertProducer.ALERT_PASSKEY_ADDED, null);
    }

    @Test
    @DisplayName("등록 실패 문구는 400 으로 보여 준다 — 알림은 없다")
    void registerFailure() throws Exception {
        loggedInSecondsAgo(30);
        when(passkeyService.register(anyLong(), anyString(), anyString()))
                .thenThrow(new PasskeyException("패스키를 확인하지 못했습니다. 다시 시도해 주세요."));
        ResponseEntity<?> r = controller.register(principal, "refresh-cookie",
                new ObjectMapper().readTree("{\"credential\":{}}"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        verify(securityAlertProducer, never()).publishAccountEvent(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 삭제 — 남의 것 · 없는 것은 404(알림 없음), 내 것이면 204 + 알림")
    void deleteOwnOnly() {
        when(passkeyService.delete(7L, 99L)).thenReturn(false);
        assertThat(controller.delete(principal, 99L).getStatusCode().value()).isEqualTo(404);
        verify(securityAlertProducer, never()).publishAccountEvent(anyLong(), any(), any(), any());

        when(passkeyService.delete(7L, 3L)).thenReturn(true);
        assertThat(controller.delete(principal, 3L).getStatusCode().value()).isEqualTo(204);
        verify(securityAlertProducer).publishAccountEvent(7L, "a@example.com", SecurityAlertProducer.ALERT_PASSKEY_REMOVED, null);
    }

    @Test
    @DisplayName("로그인 옵션 — challenge 는 HttpOnly 대기 쿠키로 묶고 본문에는 대기 값을 싣지 않는다")
    void loginOptionsSetPendingCookie() {
        when(passkeyService.authenticationOptions())
                .thenReturn(new PasskeyService.LoginOptions("pending-value", Map.of("challenge", "c")));
        ResponseEntity<?> r = controller.loginOptions(response);
        assertThat(response.getCookie(PasskeyService.COOKIE).getValue()).isEqualTo("pending-value");
        assertThat(r.getBody().toString()).doesNotContain("pending-value");
    }

    @Test
    @DisplayName("🔴 로그인 — 검증 실패면 401, 세션 없음. 대기 쿠키는 어느 경우든 지운다")
    void loginFailure() {
        when(passkeyService.authenticate("pending-value", "{}")).thenReturn(Optional.empty());
        ResponseEntity<?> r = controller.login("{}", "pending-value", request, response);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getCookie(PasskeyService.COOKIE).getMaxAge()).isZero();
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("🔴 로그인 — 정지된 계정은 패스키가 맞아도 세션 없음")
    void disabledUserBlocked() {
        user.setEnabled(false);
        when(passkeyService.authenticate("pending-value", "{}")).thenReturn(Optional.of(7L));
        assertThat(controller.login("{}", "pending-value", request, response).getStatusCode().value()).isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("로그인 성공 — 기기 기록 · 새 기기 알림 · REFRESH 쿠키(비밀번호 로그인의 끝과 같다)")
    void loginSuccess() {
        when(passkeyService.authenticate("pending-value", "{}")).thenReturn(Optional.of(7L));
        ResponseEntity<?> r = controller.login("{}", "pending-value", request, response);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getCookie("REFRESH")).isNotNull();
        verify(securityAlertProducer).publishNewDevice(eq(7L), eq("a@example.com"), any());
        assertThat(List.of(response.getCookies())).extracting(Cookie::getName).contains("DEVICE");
    }
}
