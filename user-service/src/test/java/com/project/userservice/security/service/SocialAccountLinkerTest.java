package com.project.userservice.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.service.RefreshTokenService;

/**
 * 🔴 소셜 로그인의 사전 탈취(pre-account hijacking) 방어와 연결 알림(docs/68 §15).
 *
 * <p>🐛 예전: 같은 주소면 그냥 연결했다 — 남의 주소로 먼저 가입해 둔 사람의 비밀번호가 그대로 살아 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("소셜 연결 — 사전 탈취 방어 · 연결 알림")
class SocialAccountLinkerTest {

    @Mock private UserRepository userRepository;
    @Mock private VerificationTokenRepository verificationTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private SecurityAlertProducer alerts;
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> sets;

    @InjectMocks private SocialAccountLinker linker;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().build();
        user.setUserId(7L);
        user.setEmail("victim@example.com");
        user.setPassword("$2a$attacker");
        user.setSignUpMethod("email");
        user.setEnabled(true);
        user.setEmailVerified(false);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.add(any(), any())).thenReturn(1L);
        when(passwordEncoder.encode(any())).thenReturn("$2a$random");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("🔴 주소가 확인된 적 없는 비밀번호 계정 — 비밀번호 해제 · 확인됨 · 모든 로그인 끊기 · 주인에게 알림")
    void clearsUnprovenPassword() {
        linker.link(user, "google");

        assertThat(user.getPassword()).as("먼저 가입해 둔 사람의 비밀번호가 남으면 안 된다").isEqualTo("$2a$random");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getPasswordChangedAt()).isNotNull();
        verify(refreshTokenService).revokeAll(7L);
        verify(alerts).publishAccountEvent(7L, "victim@example.com",
                SecurityAlertProducer.ALERT_UNPROVEN_PASSWORD_CLEARED, "google");
        verify(alerts, never()).publishAccountEvent(anyLong(), any(), eq(SecurityAlertProducer.ALERT_SOCIAL_LINKED), any());
    }

    @Test
    @DisplayName("인증 메일을 기다리던 계정 — 소셜 로그인이 인증을 대신한다(활성화 + 토큰 삭제)")
    void completesPendingVerification() {
        user.setEnabled(false);
        VerificationToken pending = new VerificationToken("raw", user, java.time.Duration.ofHours(24));
        when(verificationTokenRepository.findByUser(user)).thenReturn(pending);

        linker.link(user, "kakao");

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPassword()).isEqualTo("$2a$random");
        verify(verificationTokenRepository).delete(pending);
    }

    @Test
    @DisplayName("🔒 막힌 계정(정지 · 탈퇴 — 비활성, 기다리는 인증 없음)은 건드리지 않는다 — 소셜 로그인이 정지를 풀면 안 된다")
    void leavesBlockedAccountAlone() {
        user.setEnabled(false);
        when(verificationTokenRepository.findByUser(user)).thenReturn(null);

        linker.link(user, "google");

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getPassword()).isEqualTo("$2a$attacker");
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAll(any());
    }

    @Test
    @DisplayName("주소가 확인된 비밀번호 계정 — 비밀번호는 그대로, 처음 연결될 때만 알린다")
    void verifiedAccountKeepsPasswordAndAlertsOnce() {
        user.setEmailVerified(true);

        linker.link(user, "google");
        when(sets.add(any(), any())).thenReturn(0L);
        linker.link(user, "google");

        assertThat(user.getPassword()).isEqualTo("$2a$attacker");
        verify(refreshTokenService, never()).revokeAll(any());
        verify(alerts, org.mockito.Mockito.times(1)).publishAccountEvent(7L, "victim@example.com",
                SecurityAlertProducer.ALERT_SOCIAL_LINKED, "google");
    }

    @Test
    @DisplayName("소셜로 만든 계정 — 표시만 확인됨으로 맞추고, 같은 프로바이더로는 알리지 않는다")
    void socialAccountJustMarkedVerified() {
        user.setSignUpMethod("google");

        linker.link(user, "google");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getPassword()).isEqualTo("$2a$attacker");
        verify(refreshTokenService, never()).revokeAll(any());
        verify(alerts, never()).publishAccountEvent(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Redis 가 안 되면 알림만 빠지고 로그인은 이어진다")
    void redisFailureDoesNotBlockLogin() {
        user.setEmailVerified(true);
        when(sets.add(any(), any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        assertThat(linker.link(user, "google")).isSameAs(user);
        verify(alerts, never()).publishAccountEvent(anyLong(), any(), any(), any());
    }
}
