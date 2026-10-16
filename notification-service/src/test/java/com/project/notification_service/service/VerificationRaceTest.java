package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.PhoneVerification;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.PhoneVerificationRepository;
import com.project.notification_service.service.VerificationService.IssueResult;
import com.project.notification_service.service.VerificationService.VerifyResult;
import com.project.notification_service.sms.PhoneNumbers.Region;
import com.project.notification_service.sms.SmsResult;
import com.project.notification_service.sms.SmsSender;

/**
 * 인증번호 발급 · 검증의 경쟁 — 쿨다운은 먼저 잡고, 코드는 발급받은 사용자만 검증하고, 발송은 트랜잭션 밖.
 *
 * <p>[JA] 認証番号の発行・検証の競合 — クールダウンは先に取り、コードは発行されたユーザーだけが検証し、送信はトランザクションの外。
 */
@DisplayName("인증번호 — 쿨다운 선점 · 사용자 묶기 · 트랜잭션 밖 발송")
class VerificationRaceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SmsSender sender;
    private PhoneVerificationRepository repository;
    private VerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.getExpire(anyString())).thenReturn(-2L);
        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        sender = mock(SmsSender.class);
        when(sender.send(anyString(), anyString())).thenReturn(SmsResult.sent("m-1"));
        repository = mock(PhoneVerificationRepository.class);
        service = new VerificationService(repository, mock(NotificationPreferenceRepository.class), sender, redis);
        ReflectionTestUtils.setField(service, "pepper", "p");
    }

    @Test
    @DisplayName("🔴 쿨다운을 잡지 못하면(다른 요청이 방금 보냈다) 보내지 않는다 — 예전엔 동시 요청이 모두 보냈다")
    void cooldownIsTakenBeforeSending() {
        when(ops.setIfAbsent(eq("sms:cooldown:+821012345678"), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire("sms:cooldown:+821012345678")).thenReturn(-2L, 42L);

        assertThat(service.issue("01012345678", Region.KR, 7L)).isInstanceOf(IssueResult.TooMany.class);
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("발송이 실패하면 쿨다운을 풀어 바로 다시 시도할 수 있다")
    void failedSendReleasesTheCooldown() {
        when(sender.send(anyString(), anyString())).thenReturn(SmsResult.retryable("gateway down"));

        assertThat(service.issue("01012345678", Region.KR, 7L)).isInstanceOf(IssueResult.Rejected.class);
        verify(redis).delete("sms:cooldown:+821012345678");
    }

    @Test
    @DisplayName("🔴 발급은 트랜잭션 밖 — SMS 를 보내는 동안 DB 커넥션을 쥐지 않는다")
    void issueHasNoTransaction() throws NoSuchMethodException {
        assertThat(VerificationService.class.getMethod("issue", String.class, Region.class, Long.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("🔴 로그인한 사용자가 받은 코드는 다른 사람이 검증할 수 없다 — 시도 횟수도 오르지 않는다")
    void codeIsBoundToTheUser() {
        PhoneVerification mine = new PhoneVerification("+821012345678", 7L, "hash");
        when(repository.findLatestForUpdate(eq("+821012345678"), any())).thenReturn(List.of(mine));

        VerifyResult other = service.verify("01012345678", Region.KR, "000000", 8L);
        assertThat(other).isInstanceOf(VerifyResult.Rejected.class);
        assertThat(mine.getAttempts()).isZero();

        VerifyResult anonymous = service.verify("01012345678", Region.KR, "000000", null);
        assertThat(anonymous).isInstanceOf(VerifyResult.Rejected.class);
        assertThat(mine.getAttempts()).isZero();

        service.verify("01012345678", Region.KR, "000000", 7L);
        assertThat(mine.getAttempts()).as("본인의 틀린 시도만 센다").isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 없는 일일 카운터(첫 증가 뒤 expire 전에 죽은 흔적)에는 만료를 다시 건다 — 영구히 막히지 않는다")
    void countersWithoutTtlGetOne() {
        when(ops.increment(anyString())).thenReturn(4L);
        when(redis.getExpire("sms:daily:+821012345678")).thenReturn(-1L);

        service.issue("01012345678", Region.KR, null);

        verify(redis).expire("sms:daily:+821012345678", Duration.ofDays(1));
    }
}
