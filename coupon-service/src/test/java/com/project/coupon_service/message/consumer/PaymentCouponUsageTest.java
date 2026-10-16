package com.project.coupon_service.message.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxTraceContext;
import com.project.coupon_service.repository.jpa.ProcessedEventRepository;
import com.project.coupon_service.service.CouponService;

/**
 * 🔴 결제가 끝나면 쿠폰이 '사용됨' 이 된다 — 같은 쿠폰으로 다시 할인받지 못한다.
 *
 * <p>🐛 예전: Authorization 헤더가 있을 때만 처리했는데 pay-service 는 그 헤더를 싣지 않는다 → 쿠폰이 영원히 NOT_USED 였다.
 */
@DisplayName("결제 결과 → 쿠폰 사용 처리")
class PaymentCouponUsageTest {

    private CouponService couponService;
    private ProcessedEventRepository processed;
    private PaymentResponseConsumer consumer;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        processed = mock(ProcessedEventRepository.class);
        OutboxTraceContext trace = mock(OutboxTraceContext.class);
        when(trace.openFrom(any(), any())).thenReturn(OutboxTraceContext.TraceScope.NOOP);
        consumer = new PaymentResponseConsumer(new PaymentResponseMessageListener(couponService, processed), trace);
    }

    private static PaymentResponse payment(PaymentStatus status, String coupon) {
        return PaymentResponse.builder().paymentId(1L).orderId(10L).customerId(7L)
                .couponName(coupon).paymentStatus(status).build();
    }

    @Test
    @DisplayName("🔴 헤더 없이 온 결제 완료(pay-service 가 실제로 보내는 모양)도 쿠폰을 USED 로 바꾸고 원장에 남긴다")
    void completedPaymentWithoutHeaderUsesCoupon() {
        PaymentResponse r = payment(PaymentStatus.COMPLETED, "WELCOME10");
        when(couponService.useForOrder(r)).thenReturn(com.project.coupon_service.service.CouponService.UseOutcome.USED);

        consumer.receive(List.of(r), Map.of());

        verify(couponService).useForOrder(r);
        verify(processed).save(any());
    }

    @Test
    @DisplayName("쿠폰 없는 결제 · 실패 · 취소는 건드리지 않는다 — 예외도 없다(DLT 로 가지 않는다)")
    void ignoresPaymentsWithoutCouponOrNotCompleted() {
        consumer.receive(List.of(
                payment(PaymentStatus.COMPLETED, null),
                payment(PaymentStatus.COMPLETED, " "),
                payment(PaymentStatus.FAILED, "WELCOME10"),
                payment(PaymentStatus.CANCELED, "WELCOME10")), Map.of());

        verify(couponService, never()).useForOrder(any());
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("같은 결제가 다시 오면(재전달) 건너뛴다")
    void duplicateDeliverySkipped() {
        when(processed.existsById("payment-1")).thenReturn(true);

        consumer.receive(List.of(payment(PaymentStatus.COMPLETED, "WELCOME10")), Map.of());

        verify(couponService, never()).useForOrder(any());
    }

    @Test
    @DisplayName("쓸 수 있는 쿠폰이 없으면(이미 사용됨) 예외 없이 기록만 — 다시 시도해도 결과가 같다")
    void noUsableCouponDoesNotThrow() {
        PaymentResponse r = payment(PaymentStatus.COMPLETED, "WELCOME10");
        when(couponService.useForOrder(r)).thenReturn(com.project.coupon_service.service.CouponService.UseOutcome.NOT_FOUND);

        consumer.receive(List.of(r), Map.of());

        verify(processed).save(any());
    }

    @Test
    @DisplayName("🔴 다른 주문이 이미 쓴 쿠폰이면 이중 사용으로 센다(경보 CouponDoubleUse) — DLT 로 보내지 않는다")
    void doubleUseIsCounted() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(meters);
        try {
            PaymentResponse r = payment(PaymentStatus.COMPLETED, "WELCOME10");
            when(couponService.useForOrder(r)).thenReturn(com.project.coupon_service.service.CouponService.UseOutcome.USED_BY_ANOTHER_ORDER);

            consumer.receive(List.of(r), Map.of());

            org.assertj.core.api.Assertions.assertThat(meters.counter(PaymentResponseMessageListener.DOUBLE_USE_METRIC).count()).isEqualTo(1);
            verify(processed).save(any());
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(meters);
        }
    }
}
