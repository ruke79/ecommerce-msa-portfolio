package com.project.pay_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.PaymentStatus;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.repository.jpa.PaymentRepository;

/**
 * 중복 판정은 <b>요청한 상태까지</b> 같아야 한다.
 *
 * <h3>🐛 회귀 대상 — 환불이 한 번도 반영되지 않았다</h3>
 *
 * <p>예전에는 주문 id 만 보고 "완료된 응답 이벤트가 있으면 처리한 것" 으로 판정했다. 환불
 * 요청(CANCELED)은 같은 주문으로 오므로 결제 완료 응답이 있는 한 언제나 "중복" 이었다 —
 * 결제 행은 영원히 COMPLETED, order-service 는 옛 COMPLETED 응답을 다시 받아 무시, 쿠폰·재고
 * 복원과 환불 알림 전부 없었다. 화면의 주문 상태만 REFUNDED 라 환불된 것처럼 보였다.
 */
@DisplayName("pay-service — 요청 중복 판정")
class PaymentIdempotencyTest {

    private OrderOutboxHelper orderOutboxHelper;
    private PaymentResponseKafkaPublisher publisher;
    private PaymentService service;
    private OrderOutboxEvent completedResponse;

    @BeforeEach
    void setUp() {
        orderOutboxHelper = mock(OrderOutboxHelper.class);
        publisher = mock(PaymentResponseKafkaPublisher.class);
        service = new PaymentService(mock(PaymentRepository.class), orderOutboxHelper, publisher);

        completedResponse = new OrderOutboxEvent("1", "Payment", "payment-response",
                "{\"orderId\":1,\"paymentStatus\":\"COMPLETED\"}", OutboxStatus.COMPLETED);
        when(orderOutboxHelper.getCompletedOrderOutboxEvents("1", OutboxStatus.COMPLETED))
                .thenReturn(List.of(completedResponse));
        when(orderOutboxHelper.paymentStatusOf(completedResponse)).thenReturn(PaymentStatus.COMPLETED);
    }

    /** 🔴 핵심 — 결제 완료 응답이 있어도 환불 요청은 새 요청이다. */
    @Test
    @DisplayName("🔴 결제 완료 응답이 있어도 환불(CANCELED) 요청은 중복이 아니다")
    void refundIsNotADuplicateOfThePayment() {
        boolean processed = service.publishIfOutboxEventProcessedForPayment("1", PaymentStatus.CANCELED,
                OutboxStatus.COMPLETED);

        assertThat(processed).isFalse();
        verify(publisher, never()).publish(any(), any());
    }

    /** 같은 결제 요청이 다시 오면(재전달) 저장된 응답을 다시 발행한다 — 예전 동작 그대로. */
    @Test
    @DisplayName("같은 상태의 재전달은 중복이고, 저장된 응답을 다시 발행한다")
    void redeliveryOfTheSamePaymentIsADuplicate() {
        boolean processed = service.publishIfOutboxEventProcessedForPayment("1", PaymentStatus.COMPLETED,
                OutboxStatus.COMPLETED);

        assertThat(processed).isTrue();
        verify(publisher).publish(any(), any());
    }

    @Test
    @DisplayName("응답 이벤트가 없으면 처리한 적이 없다")
    void nothingStoredMeansNotProcessed() {
        when(orderOutboxHelper.getCompletedOrderOutboxEvents("2", OutboxStatus.COMPLETED)).thenReturn(List.of());

        assertThat(service.publishIfOutboxEventProcessedForPayment("2", PaymentStatus.COMPLETED,
                OutboxStatus.COMPLETED)).isFalse();
    }
}
