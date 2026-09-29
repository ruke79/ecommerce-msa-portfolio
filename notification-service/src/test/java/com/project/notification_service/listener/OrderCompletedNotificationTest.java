package com.project.notification_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

/**
 * 구매 확정 알림 (docs/25 §12).
 *
 * <p>order-service 가 {@code order-completed} 로 발행한다. 자동 확정({@code auto:true})이 이
 * 알림의 존재 이유다 — 손님이 아무것도 안 했는데 주문 상태가 바뀌는 일이라 말해 줘야 한다.
 */
@DisplayName("notification-service — 구매 확정 알림")
class OrderCompletedNotificationTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    private static String event(boolean auto) {
        return """
            {"orderId": 1234, "customerId": 7, "status": "COMPLETED", "auto": %s,
             "completedAt": "2026-09-20T10:00:00", "eventId": "order-completed:1234"}
            """.formatted(auto);
    }

    private ArgumentCaptor<String> captureBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(7L), eq(NotificationType.ORDER_COMPLETED), eq("구매가 확정되었습니다"),
                body.capture(), eq("order/1234"), eq("order-completed:1234"), any());
        return body;
    }

    @Test
    @DisplayName("🔴 자동 확정이면 '기한이 지나 자동으로' 라고 말한다 — 손님이 누른 게 아니다")
    void automaticConfirmationSaysSo() {
        listener.onOrderCompleted(List.of(event(true)), null);

        assertThat(captureBody().getValue()).contains("자동으로");
    }

    @Test
    @DisplayName("손님이 직접 확정했으면 감사 인사다")
    void customerConfirmationThanks() {
        listener.onOrderCompleted(List.of(event(false)), null);

        assertThat(captureBody().getValue()).doesNotContain("자동으로").contains("확정하셨습니다");
    }

    @Test
    @DisplayName("받는 사람이나 주문이 없으면 버린다 — DLT 로 보내지 않는다")
    void skipsIncompleteEvents() {
        listener.onOrderCompleted(List.of("{\"orderId\": 1}", "{\"customerId\": 7}"), null);

        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    /** 돈이 오가지 않으므로 문자는 아니다. 메일은 간다. */
    @Test
    @DisplayName("문자로는 나가지 않고 메일로는 나간다")
    void channels() {
        assertThat(NotificationType.ORDER_COMPLETED.isSmsWorthyByDefault()).isFalse();
        assertThat(MailKind.forNotification(NotificationType.ORDER_COMPLETED)).isEqualTo(Optional.of(MailKind.ORDER_COMPLETED));
    }
}
