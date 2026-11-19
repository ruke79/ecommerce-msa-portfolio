package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.recovery.RecoveryCodes;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;
import com.project.userservice.service.UserService.TwoFactorChange;
import com.project.userservice.util.AuthUtil;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 로그인 2단계의 복구 코드(docs/57 §22) — 인증 앱 · 휴대폰을 잃었을 때. 2단계 인증이 켜진 계정만, 맞으면 세션.
 * [JA] ログイン二段階のリカバリーコード — 認証アプリ · 携帯を失ったとき。2FA が有効なアカウントだけ、合えばセッション。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("로그인 2단계 — 복구 코드")
class RecoveryCodeLoginTest {

    @Mock private UserRepository userRepository;
    @Mock private UserService userService;
    @Mock private PendingTwoFactor pendingTwoFactor;
    @Mock private RecoveryCodes recoveryCodes;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuthUtil authUtil;

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
        when(pendingTwoFactor.reserveAttempt("pending")).thenReturn(Optional.of(new PendingTwoFactor.Attempt(7L, true)));
        when(pendingTwoFactor.peekUserId("pending")).thenReturn(Optional.of(7L));
        when(jwtUtils.createCookie(any(), any(), any())).thenAnswer(inv -> new Cookie(inv.getArgument(0), "x"));
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "7", "refresh"));
        request = new MockHttpServletRequest();
        request.setCookies(new Cookie(PendingTwoFactor.COOKIE, "pending"));
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("🔴 복구 코드가 맞으면 세션을 주고 대기 토큰을 태운다 — TOTP · SMS 를 묻지 않는다")
    void recoveryCodePasses() {
        when(recoveryCodes.verifyLogin(user, "ABCDE-FGH23")).thenReturn(TwoFactorChange.OK);

        ResponseEntity<?> r = controller.verifyPendingTwoFactor("ABCDE-FGH23", "recovery", request, response);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(refreshTokenService).createRefreshToken(7L);
        verify(pendingTwoFactor).consume("pending");
        verify(userService, never()).verifyLoginTwoFactor(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("틀린 코드는 401 · 세션 없음")
    void wrongRecoveryCodeIsRejected() {
        when(recoveryCodes.verifyLogin(user, "WRONG")).thenReturn(TwoFactorChange.INVALID_CODE);

        assertThat(controller.verifyPendingTwoFactor("WRONG", "recovery", request, response).getStatusCode().value())
                .isEqualTo(401);
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("2단계 인증이 꺼진 계정은 복구 코드로도 들어오지 못한다 — 코드를 보지도 않는다")
    void noTwoFactorNoRecovery() {
        user.setTwoFactorEnabled(false);

        assertThat(controller.verifyPendingTwoFactor("ABCDE-FGH23", "recovery", request, response).getStatusCode().value())
                .isEqualTo(401);
        verify(recoveryCodes, never()).verifyLogin(any(), anyString());
    }

    @Test
    @DisplayName("2단계 화면의 수단 목록에 복구 코드 유무를 싣는다(개수는 싣지 않는다)")
    void methodsListRecovery() {
        when(recoveryCodes.remaining(7L)).thenReturn(3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.pendingTwoFactorMethods(request).getBody();
        assertThat(body).containsEntry("recovery", true).containsEntry("totp", true);
    }

    @Test
    @DisplayName("🔴 복구 코드 만들기는 최근 로그인을 요구한다 — 훔친 세션으로 받아 가지 못한다")
    void regenerateRequiresRecentLogin() {
        when(authUtil.loggedInUser()).thenReturn(user);
        when(refreshTokenService.authTimeOf(any(), any())).thenReturn(java.util.OptionalLong.empty());

        assertThat(controller.regenerateRecoveryCodes("refresh").getStatusCode().value()).isEqualTo(403);
        verify(recoveryCodes, never()).regenerate(any());

        when(refreshTokenService.authTimeOf(any(), any()))
                .thenReturn(java.util.OptionalLong.of(java.time.Instant.now().getEpochSecond()));
        when(recoveryCodes.regenerate(user)).thenReturn(List.of("ABCDE-FGH23"));
        ResponseEntity<?> ok = controller.regenerateRecoveryCodes("refresh");
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
