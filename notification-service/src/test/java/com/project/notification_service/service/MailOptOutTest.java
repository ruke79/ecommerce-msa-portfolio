package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.repository.NotificationPreferenceRepository;

/**
 * 메일 수신 거부 — 보안 경고는 막지 않는다.
 *
 * <p>🐛 예전: 수신을 끈 사용자의 SECURITY_ALERT 메일이 큐에 들어가지 않았다. 공격자가 세션을 얻어 먼저 수신을 끄면, 이어지는
 * 비밀번호 재설정 · 소셜 연결 · 패스키 등록 경고 메일이 나가지 않았다.
 *
 * <p>[JA] メール受信拒否 — セキュリティ警告は止めない。以前は受信を切ったユーザーの SECURITY_ALERT がキューに入らなかった。
 */
@DisplayName("메일 수신 거부 — 보안 경고 예외")
class MailOptOutTest {

    private static final long USER = 7L;
    private MailService service;

    @BeforeEach
    void setUp() {
        MailMessageRepository mails = mock(MailMessageRepository.class);
        when(mails.findBySourceEventId(any())).thenReturn(Optional.empty());
        when(mails.saveAndFlush(any(MailMessage.class))).thenAnswer(i -> i.getArgument(0));

        NotificationPreference optedOut = new NotificationPreference(USER);
        optedOut.setEmailEnabled(false);
        NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
        when(preferences.findByUserId(USER)).thenReturn(Optional.of(optedOut));

        service = new MailService(mails, preferences, new ObjectMapper());
    }

    private Optional<MailMessage> enqueue(String eventId, MailKind kind) {
        return service.enqueue(eventId, "user@example.com", USER, kind, Map.of(), "ko");
    }

    @Test
    @DisplayName("🔴 수신을 꺼도 보안 경고 메일은 큐에 들어간다")
    void securityAlertIgnoresOptOut() {
        assertThat(enqueue("security-1", MailKind.SECURITY_ALERT)).isPresent();
    }

    @Test
    @DisplayName("수신을 끄면 주문 · 쿠폰 같은 알림 메일은 들어가지 않고, 본인이 요청한 메일은 들어간다")
    void otherKindsFollowThePreference() {
        assertThat(enqueue("order-1", MailKind.ORDER_PLACED)).isEmpty();
        assertThat(enqueue("coupon-1", MailKind.COUPON_ISSUED)).isEmpty();
        assertThat(enqueue("reset-1", MailKind.PASSWORD_RESET)).isPresent();
    }

    @Test
    @DisplayName("수신 거부를 무시하는 종류는 요청 메일 셋과 보안 경고뿐")
    void ignoresOptOutKinds() {
        for (MailKind kind : MailKind.values()) {
            boolean expected = kind == MailKind.REGISTRATION_CONFIRM || kind == MailKind.PASSWORD_RESET
                    || kind == MailKind.LOGIN_CHALLENGE || kind == MailKind.SECURITY_ALERT;
            assertThat(kind.ignoresOptOut()).as(kind.name()).isEqualTo(expected);
        }
    }
}
