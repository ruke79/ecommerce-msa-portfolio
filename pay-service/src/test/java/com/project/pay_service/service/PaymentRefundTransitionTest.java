package com.project.pay_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.model.Payment;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.repository.jpa.PaymentRepository;

/**
 * 환불이 결제 행에 반영되는가.
 *
 * <p>🐛 {@code persistPayment} 의 "이미 있음" 분기는 <b>기존 행을 절대 바꾸지 않았다</b> —
 * 같은 trackingId 로 무엇이 오든 저장된 상태를 그대로 되돌려 보냈다. 재전달(같은 결제가
 * 두 번 옴)에는 맞는 동작이지만 환불에는 틀리다: 돈이 돌아갔는데 결제 행은 영원히
 * COMPLETED 였다.
 */
@DisplayName("pay-service — 환불 전이")
class PaymentRefundTransitionTest {

    private PaymentRepository paymentRepository;
    private OrderOutboxHelper orderOutboxHelper;
    private PaymentService service;
    private Payment existing;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        orderOutboxHelper = mock(OrderOutboxHelper.class);
        service = new PaymentService(paymentRepository, orderOutboxHelper,
                mock(PaymentResponseKafkaPublisher.class));

        existing = Payment.builder()
                .paymentId(10L)
                .orderId(1L)
                .customerId(7L)
                .trackingId("track-1")
                .amounts(new java.math.BigDecimal("5998"))
                .paymentStatus(PaymentStatus.COMPLETED)
                .build();
        when(paymentRepository.findByTrackingId("track-1")).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static PaymentRequest request(PaymentStatus status) {
        return PaymentRequest.builder()
                .orderId(1L)
                .customerId(7L)
                .trackingId("track-1")
                .amounts(new java.math.BigDecimal("5998"))
                .paymentStatus(status)
                .build();
    }

    private PaymentResponse published() {
        ArgumentCaptor<PaymentResponse> captor = ArgumentCaptor.forClass(PaymentResponse.class);
        verify(orderOutboxHelper).saveOrderOutbox(captor.capture(), eq(OutboxStatus.STARTED));
        return captor.getValue();
    }

    @Test
    @DisplayName("🔴 CANCELED 가 오면 결제 행이 CANCELED 로 바뀌고 그 상태로 응답한다")
    void canceledRequestFlipsThePayment() {
        service.persistPayment(request(PaymentStatus.CANCELED));

        assertThat(existing.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(paymentRepository).save(existing);
        assertThat(published().getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    /** 재전달은 예전처럼 — 같은 결제가 다시 와도 행을 건드리지 않는다. */
    @Test
    @DisplayName("COMPLETED 재전달은 행을 바꾸지 않는다 — 기존 동작 그대로")
    void redeliveredCompletedIsANoOp() {
        service.persistPayment(request(PaymentStatus.COMPLETED));

        assertThat(existing.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(paymentRepository, never()).save(any());
        assertThat(published().getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    /** 🔴 순서 역전: CANCELED 뒤에 옛 COMPLETED 가 도착해도 결제가 되살아나면 안 된다. */
    @Test
    @DisplayName("🔴 CANCELED 뒤에 온 COMPLETED 는 결제를 되살리지 않는다")
    void completedAfterCanceledDoesNotResurrect() {
        service.persistPayment(request(PaymentStatus.CANCELED));
        service.persistPayment(request(PaymentStatus.COMPLETED));

        assertThat(existing.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("markCanceled 는 한 번만 참이다 — 두 번째는 아무것도 하지 않는다")
    void markCanceledIsIdempotent() {
        assertThat(existing.markCanceled()).isTrue();
        assertThat(existing.markCanceled()).isFalse();
    }

    @Test
    @DisplayName("🔴 환불 시각이 남는다 — 예전 @LastModifiedDate 는 감사가 꺼져 있어 언제나 NULL 이었다 · 두 번째 호출은 시각을 바꾸지 않는다")
    void markCanceledRecordsTime() {
        assertThat(existing.getCancelCreatedAT()).isNull();
        existing.markCanceled();
        var first = existing.getCancelCreatedAT();
        assertThat(first).isNotNull();
        existing.markCanceled();
        assertThat(existing.getCancelCreatedAT()).isSameAs(first);
    }
}
