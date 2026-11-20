package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.PendingTwoFactor;
import com.project.userservice.security.SmsTwoFactor;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;
import com.project.userservice.service.UserService.TwoFactorChange;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 로그인 2단계의 SMS 수단(docs/24 §10) — 수단마다 켜져 있어야 하고, 닿지 못하면 막고, 대기 토큰당 발송 횟수를 센다.
 *
 * <p>[JA] ログイン二段階の SMS 手段 — 手段ごとに有効でなければならず、届かなければ止め、待機トークンごとに送信回数を数える。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("로그인 2단계 — SMS 수단")
@SuppressWarnings("unchecked")
class SmsLoginTwoFactorTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private PendingTwoFactor pendingTwoFactor;
    @Mock private SmsTwoFactor smsTwoFactor;
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
        user.setSmsTwoFactorEnabled(true);     // SMS 만 켰다 ／ [JA] SMS だけ有効にした
        user.setSmsTwoFactorBinding("b");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(pendingTwoFactor.reserveAttempt("pending"))
                .thenReturn(Optional.of(new PendingTwoFactor.Attempt(7L, true)));
        when(pendingTwoFactor.peekUserId("pending")).thenReturn(Optional.of(7L));
        when(pendingTwoFactor.reserveSmsSend("pending")).thenReturn(true);
        when(jwtUtils.createCookie(any(), any(), any())).thenAnswer(inv -> new Cookie(inv.getArgument(0), "x"));
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "7", "refresh"));

        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(PendingTwoFactor.COOKIE, "pending"));
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("SMS 코드가 맞으면 세션(REFRESH)을 주고 대기 토큰을 태운다")
    void smsCodePasses() {
        when(smsTwoFactor.verifyLogin(user, "123456")).thenReturn(TwoFactorChange.OK);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("123456", "sms", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(refreshTokenService).createRefreshToken(7L);
        verify(pendingTwoFactor).consume("pending");
    }

    @Test
    @DisplayName("🔴 SMS 만 켠 계정에 TOTP 로는 들어오지 못한다 — 수단이 켜져 있어야 한다")
    void totpRejectedForSmsOnlyAccount() {
        when(userService.verifyLoginTwoFactor(anyLong(), anyInt())).thenReturn(TwoFactorChange.OK);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("123456", "totp", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("🔴 TOTP 만 켠 계정에 SMS 로는 들어오지 못한다 — notification 에 묻지도 않는다")
    void smsRejectedForTotpOnlyAccount() {
        user.setSmsTwoFactorEnabled(false);
        user.setTwoFactorEnabled(true);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("123456", "sms", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        verify(smsTwoFactor, never()).verifyLogin(any(), anyString());
        assertThat(controller.sendPendingTwoFactorSms(request).getStatusCode().value()).isEqualTo(401);
        verify(smsTwoFactor, never()).send(any());
    }

    @Test
    @DisplayName("🔴 notification 에 닿지 못하면 503 — 세션을 주지 않고 대기 토큰은 남긴다")
    void unavailableIsNotAPass() {
        when(smsTwoFactor.verifyLogin(user, "123456")).thenReturn(TwoFactorChange.UNAVAILABLE);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("123456", "sms", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
        verify(pendingTwoFactor, never()).consume(anyString());
    }

    @Test
    @DisplayName("켤 때와 번호가 다르면 409(phone_changed) — 세션 없음")
    void phoneChangedIs409() {
        when(smsTwoFactor.verifyLogin(user, "123456")).thenReturn(TwoFactorChange.PHONE_CHANGED);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("123456", "sms", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>) r.getBody()).get("code")).isEqualTo("phone_changed");
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("모르는 수단은 400 — TOTP 로 읽지 않는다")
    void unknownMethod() {
        assertThat(controller.verifyPendingTwoFactor("123456", "email", request, response).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("🔴 대기 토큰 하나로 보낼 수 있는 횟수를 넘으면 429 — notification 에 요청하지 않는다(요금)")
    void sendLimitPerPendingToken() {
        when(pendingTwoFactor.reserveSmsSend("pending")).thenReturn(false);

        assertThat(controller.sendPendingTwoFactorSms(request).getStatusCode().value()).isEqualTo(429);
        verify(smsTwoFactor, never()).send(any());
    }

    @Test
    @DisplayName("발송 — 마스킹한 번호만 돌려준다")
    void sendReturnsMaskedPhone() {
        when(smsTwoFactor.send(user)).thenReturn(new SmsTwoFactor.Sent(SmsTwoFactor.SendStatus.SENT, "+8210****5678", 5, 0));

        ResponseEntity<?> r = controller.sendPendingTwoFactorSms(request);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<Object, Object>) r.getBody()).containsEntry("maskedPhone", "+8210****5678").containsEntry("ttlMinutes", 5);
    }

    @Test
    @DisplayName("수단 목록 — 대기 토큰의 주인이 켠 것만, 대기 토큰이 없으면 401")
    void methods() {
        ResponseEntity<?> r = controller.pendingTwoFactorMethods(request);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<Object, Object>) r.getBody()).containsEntry("sms", true).containsEntry("totp", false);

        when(pendingTwoFactor.peekUserId("pending")).thenReturn(Optional.empty());
        assertThat(controller.pendingTwoFactorMethods(request).getStatusCode().value()).isEqualTo(401);
    }
}
