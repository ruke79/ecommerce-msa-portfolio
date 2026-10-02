package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(guard).recordFailure(7L);
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
        when(guard.locked(7L)).thenReturn(true);
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
}
