package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

import com.project.userservice.model.User;
import com.project.userservice.util.AuthUtil;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.TotpService;
import com.project.userservice.service.UserService;
import com.project.userservice.service.UserService.TwoFactorChange;

/**
 * 2FA 켜기 · 확인은 최근 로그인(10분)을 요구한다.
 *
 * <p>🐛 예전: 세션만 있으면 됐다 — 훔친 세션으로 공격자가 자기 인증 앱을 등록하면 주인이 다음 로그인부터 들어오지 못했다.
 *
 * <p>[JA] 2FA の有効化 · 確認は直近のログイン(10 分)を求める。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("2FA 켜기 — 최근 로그인 필요")
class TwoFactorEnableReauthTest {

    @Mock private UserService userService;
    @Mock private AuthUtil authUtil;
    @Mock private TotpService totpService;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private AuthController controller;

    private User me;

    @BeforeEach
    void setUp() {
        me = User.builder().username("kim").email("kim@example.com").build();
        me.setUserId(7L);
        when(authUtil.loggedInUser()).thenReturn(me);
        when(authUtil.loggedInUserId()).thenReturn(7L);
        when(userService.confirm2FASetup(anyLong(), anyInt())).thenReturn(TwoFactorChange.OK);
    }

    private void loggedInSecondsAgo(long seconds) {
        when(refreshTokenService.authTimeOf(eq("refresh"), any()))
                .thenReturn(OptionalLong.of(Instant.now().getEpochSecond() - seconds));
    }

    @Test
    @DisplayName("🔴 오래된 로그인(1시간 전) — 켜기 403 reauth_required, 비밀을 만들지 않는다")
    void staleSessionCannotEnable() {
        loggedInSecondsAgo(3600);

        var r = controller.enable2FA("refresh");

        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).isEqualTo("reauth_required");
        verify(userService, never()).start2FASetup(anyLong());
        verify(userService, never()).generate2FASecret(anyLong());
    }

    @Test
    @DisplayName("🔴 오래된 로그인 — 확인(실제로 켜는 순간)도 403")
    void staleSessionCannotConfirm() {
        loggedInSecondsAgo(3600);

        var r = controller.verify2FA(123456, "refresh");

        assertThat(r.getStatusCode().value()).isEqualTo(403);
        verify(userService, never()).confirm2FASetup(anyLong(), anyInt());
    }

    @Test
    @DisplayName("리프레시 쿠키가 없으면(로그인 시각을 모르면) 403")
    void unknownLoginTimeIsRejected() {
        when(refreshTokenService.authTimeOf(any(), any())).thenReturn(OptionalLong.empty());

        assertThat(controller.enable2FA(null).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("방금 로그인(1분 전) — 확인이 진행된다")
    void freshSessionConfirms() {
        loggedInSecondsAgo(60);

        var r = controller.verify2FA(123456, "refresh");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(userService).confirm2FASetup(7L, 123456);
    }
}
