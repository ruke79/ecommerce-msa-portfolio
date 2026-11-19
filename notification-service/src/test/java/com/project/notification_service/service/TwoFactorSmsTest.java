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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.notification_service.controller.TwoFactorSmsController;
import com.project.notification_service.controller.TwoFactorSmsController.TwoFactorSmsRequest;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.PhoneVerification;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.PhoneVerificationRepository;
import com.project.notification_service.service.VerificationService.TwoFactorOutcome;
import com.project.notification_service.service.VerificationService.TwoFactorResult;
import com.project.notification_service.sms.SmsResult;
import com.project.notification_service.sms.SmsSender;

/**
 * 로그인 2단계 인증의 SMS 수단(docs/24 §10) — 등록된 번호로만 · 켤 때의 번호에 묶어 · 로그인 코드는 로그인에만.
 *
 * <p>[JA] ログイン二段階認証の SMS 手段 — 登録済みの番号へのみ · 有効化時の番号に結束 · ログインコードはログインにだけ。
 */
@DisplayName("2단계 인증 SMS — 등록 번호 · 번호 결속 · 용도 분리 · 내부 전용")
class TwoFactorSmsTest {

    private static final String PHONE = "+821012345678";

    private StringRedisTemplate redis;
    private SmsSender sender;
    private PhoneVerificationRepository verifications;
    private NotificationPreferenceRepository preferences;
    private VerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.getExpire(anyString())).thenReturn(-2L);
        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        sender = mock(SmsSender.class);
        when(sender.send(anyString(), anyString())).thenReturn(SmsResult.sent("m-1"));
        verifications = mock(PhoneVerificationRepository.class);
        preferences = mock(NotificationPreferenceRepository.class);
        service = new VerificationService(verifications, preferences, sender, redis);
        ReflectionTestUtils.setField(service, "pepper", "p");
    }

    private void registered(Long userId, String phone) {
        NotificationPreference preference = new NotificationPreference(userId);
        preference.confirmPhone(phone, "KR");
        when(preferences.findByUserId(userId)).thenReturn(Optional.of(preference));
    }

    /** 실제 해시로 저장된 코드 하나 — 발송을 거쳐 만든다(해시 함수를 테스트에 복제하지 않는다). */
    private PhoneVerification sentCode(Long userId) {
        ArgumentCaptor<PhoneVerification> saved = ArgumentCaptor.forClass(PhoneVerification.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        assertThat(service.sendTwoFactor(userId, null).result()).isEqualTo(TwoFactorResult.SENT);
        verify(verifications).save(saved.capture());
        verify(sender).send(eq(PHONE), text.capture());
        PhoneVerification code = saved.getValue();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6}").matcher(text.getValue());
        assertThat(m.find()).as("문자에 6자리 코드").isTrue();
        lastCode = m.group();
        when(verifications.findLatestTwoFactorForUpdate(eq(userId), eq(PHONE), any())).thenReturn(List.of(code));
        return code;
    }

    private String lastCode;

    @Test
    @DisplayName("인증된 번호가 없으면 보내지 않는다(NO_PHONE)")
    void noPhoneNoSms() {
        when(preferences.findByUserId(7L)).thenReturn(Optional.empty());
        assertThat(service.sendTwoFactor(7L, null).result()).isEqualTo(TwoFactorResult.NO_PHONE);
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 켤 때의 번호(결속값)와 지금 번호가 다르면 보내지도 받지도 않는다 — 세션만으로 번호를 바꿔 코드를 가로채지 못하게")
    void bindingPinsThePhone() {
        registered(7L, PHONE);
        String binding = service.sendTwoFactor(7L, null).binding();
        assertThat(binding).hasSize(64).doesNotContain("1012345678");

        // 세션만 가진 사람이 번호를 자기 것으로 바꿨다
        registered(7L, "+821099998888");
        assertThat(service.sendTwoFactor(7L, binding).result()).isEqualTo(TwoFactorResult.PHONE_CHANGED);
        assertThat(service.verifyTwoFactor(7L, binding, "123456").result()).isEqualTo(TwoFactorResult.PHONE_CHANGED);
        verify(sender, never()).send(eq("+821099998888"), anyString());
    }

    @Test
    @DisplayName("🔴 로그인 코드는 LOGIN_2FA 로 저장되고, 맞으면 소비되어 다시 쓰지 못한다")
    void codeIsLoginPurposeAndSingleUse() {
        registered(7L, PHONE);
        PhoneVerification code = sentCode(7L);
        assertThat(code.getPurpose()).isEqualTo(PhoneVerification.Purpose.LOGIN_2FA);
        assertThat(code.getUserId()).isEqualTo(7L);

        TwoFactorOutcome ok = service.verifyTwoFactor(7L, null, lastCode);
        assertThat(ok.result()).isEqualTo(TwoFactorResult.VERIFIED);
        assertThat(ok.binding()).isEqualTo(service.bindingOf(PHONE));
        assertThat(service.verifyTwoFactor(7L, null, lastCode).result())
                .as("1회용").isEqualTo(TwoFactorResult.EXPIRED);
    }

    @Test
    @DisplayName("틀리면 시도를 세고 다섯 번째에는 그 코드를 버린다(EXPIRED)")
    void wrongCodesExhaustTheCode() {
        registered(7L, PHONE);
        sentCode(7L);
        String wrong = lastCode.equals("000000") ? "111111" : "000000";
        for (int i = 1; i < PhoneVerification.MAX_ATTEMPTS; i++) {
            TwoFactorOutcome o = service.verifyTwoFactor(7L, null, wrong);
            assertThat(o.result()).isEqualTo(TwoFactorResult.INVALID);
            assertThat(o.attemptsLeft()).isEqualTo(PhoneVerification.MAX_ATTEMPTS - i);
        }
        assertThat(service.verifyTwoFactor(7L, null, wrong).result()).isEqualTo(TwoFactorResult.EXPIRED);
        assertThat(service.verifyTwoFactor(7L, null, lastCode).result())
                .as("버린 코드는 맞아도 통과하지 않는다").isEqualTo(TwoFactorResult.EXPIRED);
    }

    @Test
    @DisplayName("6자리 숫자가 아니면 저장소를 열지 않고 INVALID")
    void malformedCode() {
        registered(7L, PHONE);
        assertThat(service.verifyTwoFactor(7L, null, "12ab56").result()).isEqualTo(TwoFactorResult.INVALID);
        verify(verifications, never()).findLatestTwoFactorForUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("🔴 게이트웨이를 거친 요청은 관리자라도 403 — 관리자 토큰으로 남의 로그인 코드를 보내고 맞춰 보지 못한다")
    void gatewayCallersAreRejectedEvenAdmins() {
        TwoFactorSmsController controller = new TwoFactorSmsController(service);
        TwoFactorSmsRequest request = new TwoFactorSmsRequest(7L, null, "123456");

        ResponseEntity<?> admin = controller.send(request, "ROLE_ADMIN", "a@example.com", "1");
        ResponseEntity<?> user = controller.verify(request, "ROLE_USER", "u@example.com", "7");
        assertThat(admin.getStatusCode().value()).isEqualTo(403);
        assertThat(user.getStatusCode().value()).isEqualTo(403);
        verify(sender, never()).send(anyString(), anyString());

        registered(7L, PHONE);
        assertThat(controller.send(request, null, null, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("요청의 문자열 표현에 코드가 없다(로그 · 예외 메시지)")
    void requestToStringHidesCode() {
        assertThat(new TwoFactorSmsRequest(7L, "b", "987654").toString()).doesNotContain("987654", "b]");
    }
}
