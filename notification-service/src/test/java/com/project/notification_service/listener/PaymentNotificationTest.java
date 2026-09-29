package com.project.notification_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

/**
 * 결제 응답 → 알림 종류.
 *
 * <p>🐛 회귀 대상: <b>환불 알림이 없었다.</b> "FAIL 이 아니면 전부 결제 완료" 라 pay-service 의
 * CANCELED 응답이 "결제 완료" 로 분류됐다. 그마저 중복 키가 같은 주문의 결제 완료와 겹쳐
 * 아무것도 만들어지지 않았다 — 사용자는 환불이 됐는지 알 길이 없었다.
 */
class PaymentNotificationTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    private static String response(String status) {
        return """
            {"paymentId": 10, "customerId": 7, "orderId": 1234, "amounts": 59.98, "paymentStatus": "%s"}
            """.formatted(status);
    }

    private ArgumentCaptor<String> titleFor(NotificationType type) {
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(7L), eq(type), title.capture(),
                anyString(), anyString(), anyString(), any());
        return title;
    }

    /** 🔴 핵심 — 환불은 환불이다. */
    @Test
    @DisplayName("🔴 CANCELED 응답은 환불 완료 알림이다")
    void canceledIsRefund() {
        listener.onPayment(List.of(response("CANCELED")), null);
        assertThat(titleFor(NotificationType.PAYMENT_REFUNDED).getValue()).isEqualTo("환불 완료");
    }

    @Test
    @DisplayName("COMPLETED 는 결제 완료")
    void completed() {
        listener.onPayment(List.of(response("COMPLETED")), null);
        assertThat(titleFor(NotificationType.PAYMENT_COMPLETED).getValue()).isEqualTo("결제 완료");
    }

    @Test
    @DisplayName("FAILED 는 결제 실패")
    void failed() {
        listener.onPayment(List.of(response("FAILED")), null);
        assertThat(titleFor(NotificationType.PAYMENT_FAILED).getValue()).isEqualTo("결제 실패");
    }

    /** 중복 키에 상태가 들어 있다 — 같은 주문의 결제 완료와 환불 완료는 다른 알림이다. */
    @Test
    @DisplayName("중복 키는 주문과 상태로 갈린다")
    void dedupKeyIncludesStatus() {
        listener.onPayment(List.of(response("CANCELED")), null);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), anyString(), anyString(), anyString(),
                key.capture(), any());
        assertThat(key.getValue()).isEqualTo("payment:1234:CANCELED");
    }
}
