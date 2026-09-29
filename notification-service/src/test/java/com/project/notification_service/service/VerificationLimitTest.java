package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.PhoneVerificationRepository;
import com.project.notification_service.service.VerificationService.IssueResult;
import com.project.notification_service.sms.PhoneNumbers.Region;
import com.project.notification_service.sms.SmsResult;
import com.project.notification_service.sms.SmsSender;

/**
 * 🔒 인증 SMS 발송 제한 — 번호당만이 아니라 <b>계정당·전체</b>로도 (docs/57 §10).
 *
 * <p>🐛 번호 단위 제한뿐이라 번호를 바꿔 가며 요청하면 계정 하나로 사실상 무제한 실발송을 할 수 있었다. 요금은 우리가 낸다.
 */
@DisplayName("인증 SMS 발송 제한")
class VerificationLimitTest {

    private final Map<String, Long> counters = new HashMap<>();
    private StringRedisTemplate redis;
    private SmsSender sender;
    private VerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.getExpire(anyString())).thenReturn(-2L);   // 쿨다운 없음 — 제한만 본다
        when(ops.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        sender = mock(SmsSender.class);
        when(sender.send(anyString(), anyString())).thenReturn(SmsResult.sent("m-1"));
        service = new VerificationService(mock(PhoneVerificationRepository.class),
                mock(NotificationPreferenceRepository.class), sender, redis);
        ReflectionTestUtils.setField(service, "pepper", "p");
    }

    private IssueResult issue(String phone, Long userId) {
        return service.issue(phone, Region.KR, userId);
    }

    @Test
    @DisplayName("🔴 번호를 바꿔 가며 보내도 한 계정은 하루 10회까지")
    void perUserLimitAcrossNumbers() {
        for (int i = 0; i < VerificationService.DAILY_LIMIT_PER_USER; i++) {
            assertThat(issue(String.format("0101234%04d", i), 7L)).isInstanceOf(IssueResult.Sent.class);
        }
        assertThat(issue("01099990000", 7L)).isInstanceOf(IssueResult.TooMany.class);
        verify(sender, times(VerificationService.DAILY_LIMIT_PER_USER)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("다른 계정은 따로 센다")
    void otherUserUnaffected() {
        for (int i = 0; i < VerificationService.DAILY_LIMIT_PER_USER; i++) {
            issue(String.format("0101234%04d", i), 7L);
        }
        assertThat(issue("01055550000", 8L)).isInstanceOf(IssueResult.Sent.class);
    }

    @Test
    @DisplayName("🔴 전체 일일 상한에 닿으면 누구에게도 보내지 않는다 — 요금의 천장")
    void globalCeiling() {
        ReflectionTestUtils.setField(service, "dailyGlobalLimit", 3);
        for (long u = 1; u <= 3; u++) {
            assertThat(issue("0101111000" + u, u)).isInstanceOf(IssueResult.Sent.class);
        }
        assertThat(issue("01022220000", 99L)).isInstanceOf(IssueResult.TooMany.class);
        verify(sender, times(3)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("번호당 하루 한도는 그대로")
    void perPhoneLimitStays() {
        for (long u = 1; u <= 10; u++) {
            issue("01012345678", u);
        }
        assertThat(issue("01012345678", 11L)).isInstanceOf(IssueResult.TooMany.class);
        verify(sender, times(10)).send(anyString(), anyString());
    }
}
