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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

/**
 * 🐛 부분 환불에는 알림이 없었다 (docs/49 §10).
 *
 * <p>"환불 완료" 는 결제 응답(CANCELED)에서만 나왔고, 결제는 전액이 다 돌아갔을 때만 취소된다.
 * order-service 가 환불 한 건마다 {@code order-refunded} 를 보내고, 여기서는 <b>부분</b> 환불만 알린다.
 */
@DisplayName("notification-service — 부분 환불 알림")
class OrderRefundedNotificationTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    private static String event(boolean complete, int n) {
        return """
            {"orderId": 1234, "customerId": 7, "refundRef": "R-%d", "amount": "8000", "currency": "KRW",
             "complete": %s, "refundCount": %d, "eventId": "order-refunded:1234:%d"}
            """.formatted(n, complete, n, n);
    }

    @Test
    @DisplayName("🔴 부분 환불이면 금액과 함께 '부분 환불 완료' 알림을 만든다")
    void partialRefundIsAnnounced() {
        listener.onOrderRefunded(List.of(event(false, 1)), null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(7L), eq(NotificationType.PAYMENT_REFUNDED), eq("부분 환불 완료"),
                body.capture(), eq("order/1234"), eq("order-refunded:1234:1"), any());
        assertThat(body.getValue()).contains("주문 1234").contains("8,000 KRW");
    }

    @Test
    @DisplayName("전액 환불은 건너뛴다 — 결제 응답 쪽이 '환불 완료' 를 이미 만든다(두 번 알리지 않는다)")
    void completingRefundIsLeftToThePaymentResponse() {
        listener.onOrderRefunded(List.of(event(true, 2), "{\"orderId\":1,\"customerId\":7,\"eventId\":\"x\"}"), null);

        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    @DisplayName("두 번째 부분 환불은 다른 키다 — 앞의 알림에 걸러지지 않는다")
    void eachPartialRefundHasItsOwnKey() {
        listener.onOrderRefunded(List.of(event(false, 1), event(false, 2)), null);

        verify(notificationService).create(eq(7L), any(), anyString(), anyString(), anyString(),
                eq("order-refunded:1234:1"), any());
        verify(notificationService).create(eq(7L), any(), anyString(), anyString(), anyString(),
                eq("order-refunded:1234:2"), any());
    }

    @Test
    @DisplayName("금액 표기 — 천 단위 구분, 소수는 필요한 만큼, 숫자가 아니면 문구에서 뺀다")
    void amountFormatting() {
        assertThat(NotificationEventListener.refundAmount("8000", "KRW")).isEqualTo("8,000 KRW");
        assertThat(NotificationEventListener.refundAmount("12.5", "USD")).isEqualTo("12.5 USD");
        assertThat(NotificationEventListener.refundAmount("abc", "USD")).isNull();
        assertThat(NotificationEventListener.refundAmount(null, "USD")).isNull();
    }
}
