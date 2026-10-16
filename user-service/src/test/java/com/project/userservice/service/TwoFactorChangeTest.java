package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.totp.TotpReplayGuard;
import com.project.userservice.service.UserService.TwoFactorChange;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 🔴 로그인 중의 2FA 변경 — 끄려면 지금 코드, 켜져 있으면 다시 설정 불가, 시도 제한, 알림(docs/68 §14).
 *
 * <p>🐛 예전: 끄기는 세션만 있으면 아무것도 묻지 않았고, 켜기는 이미 켜져 있어도 비밀을 덮어썼다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("2FA 변경 — 재인증 · 시도 제한 · 알림")
class TwoFactorChangeTest {

    @Mock private UserRepository userRepository;
    @Mock private TotpService totpService;
    @Mock private TotpReplayGuard guard;
    @Mock private SecurityAlertProducer alerts;
    @InjectMocks private UserServiceImpl userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("a@example.com");
        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret("SECRET");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(guard.claim(anyLong(), anyLong())).thenReturn(true);
    }

    private void codeIs(boolean right) {
        when(totpService.matchedStep(eq("SECRET"), anyInt(), anyLong()))
                .thenReturn(right ? OptionalLong.of(100) : OptionalLong.empty());
    }

    @Test
    @DisplayName("🔴 틀린 코드로는 끌 수 없다 — 실패를 세고, 알림은 없다")
    void disableNeedsTheCode() {
        codeIs(false);

        assertThat(userService.disable2FA(7L, 111111)).isEqualTo(TwoFactorChange.INVALID_CODE);

        assertThat(user.isTwoFactorEnabled()).isTrue();
        verify(guard).reserveAttempt(7L);
        verify(alerts, never()).publishTwoFactorChanged(anyLong(), any(), anyBoolean());
    }

    @Test
    @DisplayName("맞는 코드면 끄고, 비밀도 지우고, 주인에게 알린다")
    void disableWithTheCode() {
        codeIs(true);

        assertThat(userService.disable2FA(7L, 123456)).isEqualTo(TwoFactorChange.OK);

        assertThat(user.isTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        verify(guard).clearFailures(7L);
        verify(alerts).publishTwoFactorChanged(7L, "a@example.com", false);
    }

    @Test
    @DisplayName("🔴 너무 많이 틀렸으면 코드를 보지도 않는다")
    void lockedOut() {
        when(guard.maxFailures()).thenReturn(5);
        when(guard.reserveAttempt(7L)).thenReturn(6L);
        codeIs(true);

        assertThat(userService.disable2FA(7L, 123456)).isEqualTo(TwoFactorChange.TOO_MANY_ATTEMPTS);
        assertThat(user.isTwoFactorEnabled()).isTrue();
    }

    @Test
    @DisplayName("🔴 이미 켜져 있으면 다시 설정할 수 없다 — 세션만 가진 사람이 자기 앱으로 바꿔 끼우지 못하게")
    void cannotResetupWhileEnabled() {
        assertThat(userService.start2FASetup(7L)).isEqualTo(TwoFactorChange.ALREADY_ENABLED);
        assertThat(userService.confirm2FASetup(7L, 123456)).isEqualTo(TwoFactorChange.ALREADY_ENABLED);
    }

    @Test
    @DisplayName("꺼져 있으면 설정 → 맞는 코드로 켜고 알린다")
    void setup() {
        user.setTwoFactorEnabled(false);
        codeIs(true);

        assertThat(userService.start2FASetup(7L)).isEqualTo(TwoFactorChange.OK);
        assertThat(userService.confirm2FASetup(7L, 123456)).isEqualTo(TwoFactorChange.OK);
        assertThat(user.isTwoFactorEnabled()).isTrue();
        verify(alerts).publishTwoFactorChanged(7L, "a@example.com", true);
    }

    @Test
    @DisplayName("🔴 이미 쓴 구간의 코드는 맞아도 거절한다(재사용)")
    void replayIsRejected() {
        codeIs(true);
        when(guard.claim(7L, 100)).thenReturn(false);

        assertThat(userService.validate2FACode(7L, 123456)).isFalse();
    }
    /**
     * 🐛 예전: locked() 로 읽고 → 검증하고 → 틀리면 더했다 — 동시에 보낸 코드 수백 개가 모두 "잠기지 않음" 을 읽고 검증됐다.
     */
    @Test
    @DisplayName("🔴 시도는 코드를 검증하기 전에 센다 — 동시에 보낸 요청이 모두 '잠기지 않음' 을 읽지 않게")
    void attemptIsCountedBeforeVerifying() {
        codeIs(true);
        when(guard.maxFailures()).thenReturn(5);
        when(guard.reserveAttempt(7L)).thenReturn(1L);

        userService.verifyLoginTwoFactor(7L, 123456);

        var order = org.mockito.Mockito.inOrder(guard, totpService);
        order.verify(guard).reserveAttempt(7L);
        order.verify(totpService).matchedStep(eq("SECRET"), anyInt(), anyLong());
        verify(guard, never()).locked(anyLong());
    }

    // ── 로그인 2단계 (docs/68 §15) ────────────────────────────────────────

    @Test
    @DisplayName("🔴 로그인 2단계도 사용자별로 센다 — 막혀 있으면 맞는 코드도 받지 않는다")
    void loginStepUsesPerUserLimit() {
        codeIs(true);
        when(guard.maxFailures()).thenReturn(5);
        when(guard.reserveAttempt(7L)).thenReturn(6L);

        assertThat(userService.verifyLoginTwoFactor(7L, 123456)).isEqualTo(TwoFactorChange.TOO_MANY_ATTEMPTS);
        verify(guard, never()).claim(anyLong(), anyLong());
    }

    @Test
    @DisplayName("🔴 한도에 닿는 순간 한 번 알린다 — 비밀번호가 샜다는 신호")
    void alertsOnceWhenLimitReached() {
        codeIs(false);
        when(guard.maxFailures()).thenReturn(5);
        when(guard.reserveAttempt(7L)).thenReturn(4L, 5L, 6L);

        userService.verifyLoginTwoFactor(7L, 1);
        verify(alerts, never()).publishAccountEvent(anyLong(), any(), any(), any());

        userService.verifyLoginTwoFactor(7L, 1);
        userService.verifyLoginTwoFactor(7L, 1);
        verify(alerts, times(1)).publishAccountEvent(7L, "a@example.com", SecurityAlertProducer.ALERT_2FA_FAILURES, null);
    }

    @Test
    @DisplayName("로그인 2단계 — 맞는 코드면 실패 기록을 지운다, 2FA 가 꺼진 계정은 NOT_ENABLED")
    void loginStepSuccessAndNotEnabled() {
        codeIs(true);
        assertThat(userService.verifyLoginTwoFactor(7L, 123456)).isEqualTo(TwoFactorChange.OK);
        verify(guard).clearFailures(7L);

        user.setTwoFactorEnabled(false);
        assertThat(userService.verifyLoginTwoFactor(7L, 123456)).isEqualTo(TwoFactorChange.NOT_ENABLED);
    }
}
