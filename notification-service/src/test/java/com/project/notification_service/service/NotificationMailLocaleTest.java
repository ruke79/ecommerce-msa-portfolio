package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.NotificationRepository;

/**
 * 알림 메일 — 지역(region)을 모르는 사용자도 알림이 만들어지고 메일이 큐에 들어간다.
 *
 * <p>🐛 2026-10-24 운영: 지역은 전화번호를 인증해야 채워진다. 인증하지 않은 사용자(대부분)는 {@code Locale.forLanguageTag(null)} 이
 * NPE 를 던졌고, 그 줄이 메일 실패를 막는 try 밖이라 <b>알림 생성 전체</b>가 실패했다 — 주문 접수 · 결제 완료 안내가 10-16 부터 모두
 * {@code order.DLT} · {@code payment.DLT}(notification 그룹)로 갔다(배송 · 쿠폰 · 재입고 안내도 같은 길).
 *
 * <p>[JA] 地域を知らない利用者でも通知が作られ、メールがキューに入る。以前は Locale.forLanguageTag(null) の NPE で通知の作成ごと失敗した。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 메일 — 지역을 모르는 사용자(전화 미인증)")
class NotificationMailLocaleTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @Mock
    private MailService mailService;

    @InjectMocks
    private NotificationService service;

    private NotificationPreference preference;

    @BeforeEach
    void setUp() {
        preference = new NotificationPreference(7L);
        preference.rememberEmail("buyer@example.com");   // 지역은 비어 있다 — 전화번호를 인증하지 않았다
        when(preferenceRepository.findByUserId(7L)).thenReturn(Optional.of(preference));
        when(notificationRepository.findBySourceEventId(anyString())).thenReturn(Optional.empty());
        when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("🔴 주문 접수 — 알림이 만들어지고, 메일은 제목 그대로 · 언어는 기본값(null)으로 큐에")
    @SuppressWarnings("unchecked")
    void unknownRegionStillNotifiesAndMails() {
        Optional<Notification> created = service.create(7L, NotificationType.ORDER_PLACED,
                "주문 접수", "주문 1 이 접수되었습니다.", "/order/1", "order:1", Map.of("subject", "주문 1"));

        assertThat(created).as("알림 자체가 만들어진다(예전에는 NPE → DLT)").isPresent();
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(mailService).enqueue(eq("mail:order:1"), eq("buyer@example.com"), eq(7L), eq(MailKind.ORDER_PLACED),
                params.capture(), isNull());
        assertThat(params.getValue()).containsEntry("subject", "주문 접수").containsEntry("url", "/order/1");
    }

    @Test
    @DisplayName("일본 지역이면 메일 언어 ja — 지역을 아는 사용자는 예전과 같다")
    void knownRegionKeepsLocale() {
        preference.confirmPhone("+819012345678", "JP");

        assertThat(service.create(7L, NotificationType.PAYMENT_COMPLETED, "결제 완료", "…", "/order/2", "payment:2:PAID",
                Map.of())).isPresent();

        verify(mailService).enqueue(eq("mail:payment:2:PAID"), eq("buyer@example.com"), eq(7L),
                eq(MailKind.PAYMENT_COMPLETED), anyMap(), eq("ja"));
    }

    @Test
    @DisplayName("메일 큐가 실패해도 알림은 만들어진다")
    void mailFailureDoesNotBreakNotification() {
        when(mailService.enqueue(any(), any(), any(), any(), anyMap(), any())).thenThrow(new IllegalStateException("smtp"));

        assertThat(service.create(7L, NotificationType.ORDER_PLACED, "주문 접수", "…", "/order/3", "order:3", Map.of()))
                .isPresent();
    }
}
