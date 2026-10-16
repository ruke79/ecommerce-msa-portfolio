package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.dto.WithdrawalStatusDto;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.ReviewLikeRepository;
import com.project.userservice.repository.jpa.ShippingAddressRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.repository.jpa.WishiItemRepository;

/**
 * 회원 탈퇴의 <b>되돌릴 수 있는 구간</b>을 고정한다.
 *
 * <p>최종 처리는 되돌릴 수 없으므로, "처리한다"보다 "처리하지 않는다"를 더 많이 검증한다 —
 * 그 사이 취소한 계정, 이미 처리한 계정, 비밀번호가 틀린 신청.
 */
@DisplayName("회원 탈퇴")
class WithdrawalServiceTest {

    private static final Long USER_ID = 1234567890123456789L;
    private static final String PASSWORD = "correct-horse";

    private UserRepository userRepository;
    private ShippingAddressRepository shippingAddressRepository;
    private WishiItemRepository wishItemRepository;
    private VerificationTokenRepository verificationTokenRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private ReviewLikeRepository reviewLikeRepository;
    private PasswordEncoder passwordEncoder;
    private com.project.userservice.message.UserOutboxHelper outboxHelper;
    private com.project.userservice.service.RefreshTokenService refreshTokenService;
    private com.project.userservice.repository.jpa.KnownDeviceRepository knownDeviceRepository;
    private com.project.userservice.repository.jpa.PasskeyCredentialRepository passkeyCredentialRepository;
    private com.project.userservice.repository.jpa.NotificationRepository notificationRepository;
    private WithdrawalService service;
    private com.project.userservice.security.CurrentPasswordGuard currentPasswordGuard;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        shippingAddressRepository = mock(ShippingAddressRepository.class);
        wishItemRepository = mock(WishiItemRepository.class);
        verificationTokenRepository = mock(VerificationTokenRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        reviewLikeRepository = mock(ReviewLikeRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        outboxHelper = mock(com.project.userservice.message.UserOutboxHelper.class);
        refreshTokenService = mock(com.project.userservice.service.RefreshTokenService.class);
        when(refreshTokenService.authTimeOf(any(), any())).thenReturn(java.util.OptionalLong.empty());
        knownDeviceRepository = mock(com.project.userservice.repository.jpa.KnownDeviceRepository.class);
        passkeyCredentialRepository = mock(com.project.userservice.repository.jpa.PasskeyCredentialRepository.class);
        notificationRepository = mock(com.project.userservice.repository.jpa.NotificationRepository.class);
        currentPasswordGuard = mock(com.project.userservice.security.CurrentPasswordGuard.class);

        user = new User("tester", "tester@example.com", passwordEncoder.encode(PASSWORD));
        user.setUserId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service = new WithdrawalService(userRepository, shippingAddressRepository,
                wishItemRepository, verificationTokenRepository, passwordResetTokenRepository,
                reviewLikeRepository, passwordEncoder, outboxHelper, refreshTokenService,
                knownDeviceRepository, passkeyCredentialRepository, notificationRepository, currentPasswordGuard);
        ReflectionTestUtils.setField(service, "grace", Duration.ofDays(7));
    }

    // ── 신청 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신청하면 예정일이 유예 기간만큼 뒤로 잡힌다")
    void requestSchedulesAfterGrace() {
        WithdrawalStatusDto status = service.request(USER_ID, PASSWORD, "안 씀");

        assertThat(status.pending()).isTrue();
        assertThat(user.getWithdrawalRequestedAt()).isNotNull();
        assertThat(status.scheduledAt())
                .isEqualTo(user.getWithdrawalRequestedAt().plusDays(7));
        assertThat(status.daysLeft()).isEqualTo(7);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 신청되지 않는다 — 되돌릴 수 없는 동작의 본인 확인")
    void wrongPasswordDoesNotRequest() {
        assertThatThrownBy(() -> service.request(USER_ID, "wrong", null))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getWithdrawalRequestedAt()).isNull();
        verify(userRepository, never()).save(user);
    }

    @Test
    @DisplayName("비밀번호를 안 보내도 신청되지 않는다")
    void missingPasswordDoesNotRequest() {
        assertThatThrownBy(() -> service.request(USER_ID, null, null))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getWithdrawalRequestedAt()).isNull();
    }

    /**
     * 🐛 예전 테스트는 소셜 계정을 {@code password = null} 로 만들었다 — 실제 소셜 가입은 <b>아무도 모르는 난수</b>를 넣으므로 그 계정은
     * 존재하지 않는다. 테스트가 통과하는 동안 실제 소셜 가입자는 탈퇴할 수 없었다(docs/68 §16). 이제 실제와 같은 모양으로 만든다.
     */
    @Test
    @DisplayName("🔴 소셜 계정(난수 비밀번호)은 비밀번호 없이, 최근 로그인으로 신청한다")
    void socialAccountUsesRecentLogin() {
        user.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        when(refreshTokenService.authTimeOf("cookie", user))
                .thenReturn(java.util.OptionalLong.of(java.time.Instant.now().minusSeconds(120).getEpochSecond()));

        WithdrawalStatusDto status = service.request(USER_ID, null, null, "cookie");

        assertThat(status.pending()).isTrue();
    }

    @Test
    @DisplayName("🔒 비밀번호도 없고 로그인이 오래됐으면 거절 — 열어 둔 공용 PC 의 세션만으로는 탈퇴할 수 없다")
    void staleLoginWithoutPasswordRejected() {
        when(refreshTokenService.authTimeOf("cookie", user))
                .thenReturn(java.util.OptionalLong.of(java.time.Instant.now().minusSeconds(3600).getEpochSecond()));

        assertThatThrownBy(() -> service.request(USER_ID, null, null, "cookie"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("다시 로그인한 뒤 10분 안에");
        assertThat(user.getWithdrawalRequestedAt()).isNull();
    }

    @Test
    @DisplayName("🔒 비밀번호를 넣었는데 틀리면 최근 로그인이 있어도 거절 — 다른 길로 넘어가지 않는다")
    void wrongPasswordDoesNotFallBackToRecentLogin() {
        when(refreshTokenService.authTimeOf(any(), any()))
                .thenReturn(java.util.OptionalLong.of(java.time.Instant.now().getEpochSecond()));

        assertThatThrownBy(() -> service.request(USER_ID, "wrong", null, "cookie"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    @DisplayName("다시 신청해도 예정일이 밀리지 않는다")
    void repeatedRequestDoesNotResetTheClock() {
        LocalDateTime first = LocalDateTime.now().minusDays(3);
        user.setWithdrawalRequestedAt(first);

        WithdrawalStatusDto status = service.request(USER_ID, PASSWORD, "또 눌렀다");

        assertThat(user.getWithdrawalRequestedAt()).isEqualTo(first);
        assertThat(status.daysLeft()).isEqualTo(4);
    }

    @Test
    @DisplayName("남은 기간은 올림한다 — 23시간 남았는데 '0일'이면 이미 지난 것처럼 읽힌다")
    void daysLeftRoundsUp() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(6).minusHours(1));

        assertThat(service.status(USER_ID).daysLeft()).isEqualTo(1);
    }

    // ── 취소 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("취소하면 신청 기록이 사라진다")
    void cancelClearsTheRequest() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(1));
        user.setWithdrawalReason("사유");

        WithdrawalStatusDto status = service.cancel(USER_ID);

        assertThat(status.pending()).isFalse();
        assertThat(user.getWithdrawalRequestedAt()).isNull();
        assertThat(user.getWithdrawalReason()).isNull();
    }

    @Test
    @DisplayName("신청한 적 없는 계정의 취소는 아무 일도 하지 않는다")
    void cancelWithoutRequestIsANoOp() {
        service.cancel(USER_ID);

        verify(userRepository, never()).save(user);
    }

    // ── 최종 처리 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("최종 처리는 식별정보를 지우고 로그인 경로를 닫는다")
    void finalizeAnonymizes() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(8));
        String oldPasswordHash = user.getPassword();

        assertThat(service.finalizeWithdrawal(USER_ID)).isTrue();

        assertThat(user.getEmail()).doesNotContain("tester@example.com");
        assertThat(user.getUsername()).isNotEqualTo("tester");
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getWithdrawnAt()).isNotNull();
        // 옛 비밀번호로는 더 이상 들어올 수 없다
        assertThat(user.getPassword()).isNotEqualTo(oldPasswordHash);
        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isFalse();
    }

    @Test
    @DisplayName("익명화한 username 은 20자 제한을 넘지 않는다")
    void anonymizedUsernameFitsTheColumn() {
        user.setUserId(Long.MAX_VALUE);          // 가장 긴 식별자
        when(userRepository.findById(Long.MAX_VALUE)).thenReturn(Optional.of(user));
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(8));

        service.finalizeWithdrawal(Long.MAX_VALUE);

        // @Size(max = 20) 이다. 10진수 TSID 를 그대로 쓰면 19자 + 접두사로 넘친다.
        assertThat(user.getUsername().length()).isLessThanOrEqualTo(20);
        assertThat(user.getEmail().length()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("🔴 최종 처리는 열려 있던 로그인을 지금 끊는다 — 비활성만으로는 다음 갱신까지 · 채팅 연결은 계속 살아 있었다")
    void finalizeEndsSessions() {
        user.setWithdrawalRequestedAt(java.time.LocalDateTime.now().minusDays(8));

        service.finalizeWithdrawal(USER_ID);

        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    @DisplayName("최종 처리는 부속 개인정보를 함께 지운다")
    void finalizeDeletesPersonalData() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(8));

        service.finalizeWithdrawal(USER_ID);

        verify(shippingAddressRepository).deleteByUserId(USER_ID);
        verify(wishItemRepository).deleteByUserId(USER_ID);
        verify(reviewLikeRepository).deleteByUserId(USER_ID);
        verify(verificationTokenRepository).deleteByUserId(USER_ID);
        verify(passwordResetTokenRepository).deleteByUserId(USER_ID);
    }

    /** 🐛 예전에는 기기 · 패스키 · 알림(실제 이메일) · 탈퇴 사유가 남아, 탈퇴 7일 뒤에도 원래 주소가 DB 에서 나왔다. */
    @Test
    @DisplayName("🔴 최종 처리는 기기 · 패스키 · 알림(옛 행의 실제 이메일 포함) · 탈퇴 사유도 지운다")
    void finalizeDeletesDevicesPasskeysNotificationsAndReason() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(8));
        user.setWithdrawalReason("010-1234-5678 로 연락 주세요");

        service.finalizeWithdrawal(USER_ID);

        verify(knownDeviceRepository).deleteByUserId(USER_ID);
        verify(passkeyCredentialRepository).deleteByUserId(USER_ID);
        verify(notificationRepository).deleteByOwners(
                java.util.List.of(String.valueOf(USER_ID), "tester@example.com"));
        assertThat(user.getWithdrawalReason()).isNull();
    }

    /** 🔴 원본만 지우면 chat 의 복제본에 이메일·이름·사진이 남는다(docs/48 §6-⑤). */
    @Test
    @DisplayName("🔴 최종 처리는 user-deleted 를 같은 트랜잭션의 아웃박스에 적는다 — 복제본도 지우게")
    void finalizeTellsReplicas() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(8));

        service.finalizeWithdrawal(USER_ID);

        verify(outboxHelper).recordUserDeleted(USER_ID);
    }

    @Test
    @DisplayName("그 사이 취소한 계정은 건드리지 않는다")
    void finalizeSkipsCancelledAccounts() {
        // withdrawalRequestedAt 이 null = 취소했다
        assertThat(service.finalizeWithdrawal(USER_ID)).isFalse();

        assertThat(user.getEmail()).isEqualTo("tester@example.com");
        verify(shippingAddressRepository, never()).deleteByUserId(USER_ID);
        verify(outboxHelper, never()).recordUserDeleted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("이미 처리된 계정을 두 번 처리하지 않는다")
    void finalizeIsNotRepeated() {
        user.setWithdrawalRequestedAt(LocalDateTime.now().minusDays(30));
        user.setWithdrawnAt(LocalDateTime.now().minusDays(23));

        assertThat(service.finalizeWithdrawal(USER_ID)).isFalse();

        verify(shippingAddressRepository, never()).deleteByUserId(USER_ID);
    }

    /** 🐛 예전: 현재 비밀번호 확인에 제한이 없어, 세션만 가진 사람이 탈퇴 신청으로 비밀번호를 대입할 수 있었다. */
    @Test
    @DisplayName("🔴 비밀번호 확인은 비교 전에 시도를 센다 — 한도를 넘으면 비교하지 않는다")
    void passwordAttemptsAreLimited() {
        org.mockito.Mockito.doThrow(new com.project.userservice.security.CurrentPasswordGuard.TooManyAttemptsException())
                .when(currentPasswordGuard).reserve(USER_ID);

        assertThatThrownBy(() -> service.request(USER_ID, PASSWORD, null))
                .isInstanceOf(com.project.userservice.security.CurrentPasswordGuard.TooManyAttemptsException.class);
        assertThat(user.getWithdrawalRequestedAt()).isNull();
    }
}
