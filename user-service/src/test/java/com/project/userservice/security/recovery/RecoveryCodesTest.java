package com.project.userservice.security.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.TwoFactorRecoveryCode;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.TwoFactorRecoveryCodeRepository;
import com.project.userservice.security.totp.TotpReplayGuard;
import com.project.userservice.service.UserService.TwoFactorChange;

/**
 * 2단계 인증 복구 코드(docs/57 §22). 🐛 예전에는 복구 수단이 없어 인증 앱 · 번호를 잃으면 들어올 수 없었다.
 * [JA] 二段階認証のリカバリーコード。以前は復旧手段がなく、認証アプリ · 番号を失うと入れなかった。
 */
@DisplayName("2단계 인증 복구 코드")
class RecoveryCodesTest {

    private TwoFactorRecoveryCodeRepository repository;
    private TotpReplayGuard attempts;
    private SecurityAlertProducer alerts;
    private RecoveryCodes codes;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(TwoFactorRecoveryCodeRepository.class);
        attempts = mock(TotpReplayGuard.class);
        when(attempts.maxFailures()).thenReturn(5);
        when(attempts.reserveAttempt(anyLong())).thenReturn(1L);
        alerts = mock(SecurityAlertProducer.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        codes = new RecoveryCodes("test-pepper-test-pepper-test-pepper-01", repository, attempts, alerts, tm);
        user = new User("kim", "kim@example.com", "pw");
        user.setUserId(42L);
        user.setTwoFactorEnabled(true);
    }

    @SuppressWarnings("unchecked")
    private List<TwoFactorRecoveryCode> saved() {
        ArgumentCaptor<Iterable<TwoFactorRecoveryCode>> rows = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(rows.capture());
        List<TwoFactorRecoveryCode> out = new ArrayList<>();
        rows.getValue().forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("🔴 10개를 만들고, DB 에는 원문이 아니라 HMAC 만 · 옛 코드는 지우고 · 주인에게 알린다")
    void regenerateStoresOnlyHashes() {
        List<String> plain = codes.regenerate(user);

        assertThat(plain).hasSize(RecoveryCodes.COUNT).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[2-9A-HJKMNP-Z]{5}-[2-9A-HJKMNP-Z]{5}"));
        verify(repository).deleteByUserId(42L);
        List<TwoFactorRecoveryCode> rows = saved();
        Set<String> hashes = new HashSet<>();
        for (TwoFactorRecoveryCode r : rows) {
            hashes.add(r.getCodeHash());
            assertThat(r.getCodeHash()).hasSize(64);
            assertThat(plain).noneMatch(c -> r.getCodeHash().contains(c.replace("-", "")));
        }
        assertThat(hashes).containsExactlyInAnyOrderElementsOf(
                plain.stream().map(c -> codes.hash(RecoveryCodes.normalize(c))).toList());
        verify(alerts).publishAccountEvent(42L, "kim@example.com", SecurityAlertProducer.ALERT_RECOVERY_CODES_CREATED, null);
    }

    @Test
    @DisplayName("2단계 인증이 꺼져 있으면 만들지 않는다")
    void noCodesWithoutTwoFactor() {
        user.setTwoFactorEnabled(false);
        assertThat(codes.regenerate(user)).isEmpty();
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("🔴 맞는 코드(소문자 · 하이픈 · 공백 무시)는 한 번 쓰이고, 실패 칸을 비우고, 주인에게 알린다")
    void validCodeIsConsumedOnce() {
        String code = "ABCDE-FGH23";
        when(repository.consume(eq(42L), eq(codes.hash("ABCDEFGH23")), any())).thenReturn(1);

        assertThat(codes.verifyLogin(user, " abcde-fgh23 ")).isEqualTo(TwoFactorChange.OK);
        verify(attempts).clearFailures(42L);
        verify(alerts).publishAccountEvent(42L, "kim@example.com", SecurityAlertProducer.ALERT_RECOVERY_CODE_USED, null);

        // 이미 쓴 코드 — UPDATE 가 0 행
        when(repository.consume(eq(42L), eq(codes.hash("ABCDEFGH23")), any())).thenReturn(0);
        assertThat(codes.verifyLogin(user, code)).isEqualTo(TwoFactorChange.INVALID_CODE);
    }

    @Test
    @DisplayName("시도는 TOTP · SMS 와 같은 칸 — 한도를 넘으면 코드를 보지도 않는다 · 형식이 틀려도 센다")
    void attemptsShareTheTwoFactorLimit() {
        when(attempts.reserveAttempt(42L)).thenReturn(6L);
        assertThat(codes.verifyLogin(user, "ABCDE-FGH23")).isEqualTo(TwoFactorChange.TOO_MANY_ATTEMPTS);
        verify(repository, never()).consume(anyLong(), anyString(), any());

        when(attempts.reserveAttempt(42L)).thenReturn(5L);
        assertThat(codes.verifyLogin(user, "0000")).isEqualTo(TwoFactorChange.INVALID_CODE);
        verify(alerts).publishAccountEvent(42L, "kim@example.com", SecurityAlertProducer.ALERT_2FA_FAILURES, null);
    }

    @Test
    @DisplayName("2단계 인증이 모두 꺼지면 지운다 — 하나라도 켜져 있으면 남긴다")
    void forgetOnlyWhenAllOff() {
        codes.forgetIfNoTwoFactor(user);
        verify(repository, never()).deleteByUserId(42L);
        user.setTwoFactorEnabled(false);
        codes.forgetIfNoTwoFactor(user);
        verify(repository).deleteByUserId(42L);
        assertThat(codes.verifyLogin(user, "ABCDE-FGH23")).isEqualTo(TwoFactorChange.NOT_ENABLED);
    }
}
