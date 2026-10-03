package com.project.userservice.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;

/**
 * 미인증 계정 정리의 <b>안전장치</b>를 센다.
 *
 * <p>계정 삭제는 되돌릴 수 없다. 그래서 "지운다"보다 "지우지 않는다"를 더 많이 검증한다 —
 * 기본값이 꺼짐인지, 그 사이 인증한 계정을 건드리지 않는지, 외래키가 막았을 때 나머지를
 * 계속 진행하는지.
 *
 * <p>JPQL 자체는 여기서 검증되지 않는다(실제 DB 가 필요하다). 이 테스트가 고정하는 것은
 * 스케줄러의 판단과 순서다.
 */
@DisplayName("미인증 계정 정리")
class UnverifiedAccountCleanupTest {

    private VerificationTokenRepository tokenRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private UserRepository userRepository;
    private UnverifiedAccountCleanup cleanup;

    @BeforeEach
    void setUp() {
        tokenRepository = mock(VerificationTokenRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        userRepository = mock(UserRepository.class);

        // 실제 트랜잭션은 필요 없다. 콜백을 그대로 실행하고 예외는 그대로 올린다.
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));

        cleanup = new UnverifiedAccountCleanup(
                tokenRepository, passwordResetTokenRepository, userRepository, tx);
        ReflectionTestUtils.setField(cleanup, "batchSize", 200);
        ReflectionTestUtils.setField(cleanup, "deleteAccounts", true);
    }

    private VerificationToken staleToken(long tokenId, boolean userEnabled) {
        User user = new User("tester" + tokenId, "t" + tokenId + "@example.com", "pw");
        user.setUserId(tokenId * 10);
        user.setEnabled(userEnabled);

        VerificationToken token = mock(VerificationToken.class);
        when(token.getId()).thenReturn(tokenId);
        when(token.getUser()).thenReturn(user);
        when(tokenRepository.findById(tokenId)).thenReturn(Optional.of(token));
        return token;
    }

    private void givenCandidates(VerificationToken... tokens) {
        when(tokenRepository.findStaleUnverified(any(Date.class), any(Pageable.class)))
                .thenReturn(List.of(tokens));
    }

    @Test
    @DisplayName("delete-accounts=false(기본)면 한 건도 지우지 않는다")
    void doesNotDeleteWhenSwitchIsOff() {
        ReflectionTestUtils.setField(cleanup, "deleteAccounts", false);
        givenCandidates(staleToken(1L, false), staleToken(2L, false));

        cleanup.cleanUpAccounts(new Date());

        verify(userRepository, never()).delete(any(User.class));
        verify(tokenRepository, never()).delete(any(VerificationToken.class));
    }

    @Test
    @DisplayName("비활성 계정은 토큰과 함께 지운다")
    void deletesUnverifiedAccount() {
        givenCandidates(staleToken(1L, false));

        cleanup.cleanUpAccounts(new Date());

        ArgumentCaptor<User> deleted = ArgumentCaptor.forClass(User.class);
        verify(userRepository).delete(deleted.capture());
        assertThat(deleted.getValue().getUserId()).isEqualTo(10L);
        verify(tokenRepository).delete(any(VerificationToken.class));
    }

    @Test
    @DisplayName("조회와 삭제 사이에 인증을 마친 계정은 건드리지 않는다")
    void skipsAccountVerifiedInTheMeantime() {
        // 후보로 뽑힌 뒤 사용자가 링크를 눌러 활성화된 경우 — 재조회에서 걸러진다.
        givenCandidates(staleToken(1L, true));

        cleanup.cleanUpAccounts(new Date());

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("다른 레플리카가 먼저 처리해 토큰이 사라졌으면 건너뛴다")
    void skipsWhenTokenAlreadyGone() {
        VerificationToken token = staleToken(1L, false);
        when(tokenRepository.findById(1L)).thenReturn(Optional.empty());
        givenCandidates(token);

        cleanup.cleanUpAccounts(new Date());

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("외래키가 막은 계정은 건너뛰고 나머지는 계속 지운다")
    void foreignKeyViolationDoesNotStopTheBatch() {
        givenCandidates(staleToken(1L, false), staleToken(2L, false));
        // 1번 계정에는 주문·리뷰 같은 참조가 있어 DB 가 삭제를 거부한다.
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("FK"))
                .when(userRepository).delete(argThatUserIdIs(10L));

        cleanup.cleanUpAccounts(new Date());

        // 2번은 그대로 지워져야 한다 — 하나가 막혔다고 배치가 멈추면 안 된다.
        verify(userRepository, times(2)).delete(any(User.class));
    }

    @Test
    @DisplayName("후보가 없으면 아무 일도 하지 않는다")
    void noCandidatesIsANoOp() {
        when(tokenRepository.findStaleUnverified(any(Date.class), any(Pageable.class)))
                .thenReturn(List.of());

        cleanup.cleanUpAccounts(new Date());

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("만료 토큰 정리는 활성 계정의 확인 토큰만 지운다 — 비활성 계정의 토큰은 판정 근거다")
    void purgeKeepsTokensOfDisabledAccounts() {
        Date now = new Date();

        cleanup.purgeExpiredTokens(now);

        verify(tokenRepository).deleteExpiredOfEnabledUsers(now);
        // 전체 삭제 메서드를 쓰면 유예 기간 중인 계정의 근거가 사라진다.
        verify(tokenRepository, never()).deleteAllExpiredSince(any(Date.class));
        verify(passwordResetTokenRepository).deleteAllExpiredSince(now.toInstant());
    }

    @Test
    @DisplayName("유예 기간이 컷오프에 반영된다 — 방금 만료된 토큰은 대상이 아니다")
    void graceIsAppliedToTheCutoff() {
        ReflectionTestUtils.setField(cleanup, "grace", java.time.Duration.ofDays(7));
        ReflectionTestUtils.setField(cleanup, "enabled", true);
        ReflectionTestUtils.setField(cleanup, "deleteAccounts", false);
        when(tokenRepository.findStaleUnverified(any(Date.class), any(Pageable.class)))
                .thenReturn(List.of());

        long before = System.currentTimeMillis();
        cleanup.run();

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(tokenRepository).findStaleUnverified(cutoff.capture(), any(Pageable.class));

        long expected = before - TimeUnit.DAYS.toMillis(7);
        assertThat(cutoff.getValue().getTime())
                .isBetween(expected - 5_000, expected + 5_000);
    }

    @Test
    @DisplayName("enabled=false 면 스케줄러가 통째로 쉰다")
    void disabledSchedulerDoesNothing() {
        ReflectionTestUtils.setField(cleanup, "enabled", false);

        cleanup.run();

        verify(tokenRepository, never()).findStaleUnverified(any(Date.class), any(Pageable.class));
        verify(tokenRepository, never()).deleteExpiredOfEnabledUsers(any(Date.class));
    }

    private static User argThatUserIdIs(long userId) {
        return org.mockito.ArgumentMatchers.argThat(
                user -> user != null && Long.valueOf(userId).equals(user.getUserId()));
    }
}
