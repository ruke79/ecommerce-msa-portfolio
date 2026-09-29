package com.project.pay_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.pay_service.model.Payment;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.repository.jpa.PaymentRepository;

/**
 * 결제 행은 <b>어느 제공자의 어느 결제인가</b>를 제공자와 무관하게 적는다 (V5, docs/49 §3).
 *
 * <p>예전에는 PayPal 참조만 {@code paypal_order_id} 에 적었다 — Stripe·포트원·무통장 결제는 참조가 남지 않았다. 앞
 * 릴리스의 order 가 v2 로 쓴 레코드(제공자 필드 없음)는 결제수단에서 정한다.
 */
@DisplayName("pay-service — 제공자 중립 결제 참조")
class PaymentProviderRecordTest {

    private PaymentRepository repository;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        when(repository.findByTrackingId(any())).thenReturn(Optional.empty());
        when(repository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        service = new PaymentService(repository, mock(OrderOutboxHelper.class), mock(PaymentResponseKafkaPublisher.class));
    }

    private Payment saved(PaymentRequest request) {
        service.persistPayment(request);
        ArgumentCaptor<Payment> c = ArgumentCaptor.forClass(Payment.class);
        verify(repository).save(c.capture());
        return c.getValue();
    }

    private static PaymentRequest.PaymentRequestBuilder base(PaymentType type) {
        return PaymentRequest.builder().orderId(1L).customerId(7L).trackingId("t1")
                .amounts(new java.math.BigDecimal("59.98")).paymentType(type)
                .paymentStatus(PaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("v3 요청 — 제공자와 참조를 그대로 적는다(Stripe 도)")
    void v3RequestIsRecordedAsIs() {
        Payment p = saved(base(PaymentType.STRIPE)
                .paymentProvider(PaymentProvider.STRIPE).providerPaymentId("cs_1").build());

        assertThat(p.getPaymentProvider()).isEqualTo(PaymentProvider.STRIPE);
        assertThat(p.getProviderPaymentId()).isEqualTo("cs_1");
    }

    @Test
    @DisplayName("v2 레코드(A 의 order) — 제공자는 결제수단에서 정한다. 참조는 v4 에서 빠진 필드에 있어 비어 있다")
    void v2PaypalRequestFallsBack() {
        // v2 의 PayPal 참조는 옛 paypalOrderId 에만 있었고 그 필드는 스키마 v4 에서 빠졌다 — 읽으면 비어 있다.
        // 원본 참조는 order 의 provider_payment_id 에 있다(docs/49 §3 C2).
        Payment p = saved(base(PaymentType.PAYPAL).build());

        assertThat(p.getPaymentProvider()).isEqualTo(PaymentProvider.PAYPAL);
        assertThat(p.getProviderPaymentId()).isNull();
    }

    @Test
    @DisplayName("v2 레코드의 무통장(CASH)은 BANK_TRANSFER — v2 에는 참조가 없다")
    void v2CashRequestMapsToBankTransfer() {
        Payment p = saved(base(PaymentType.CASH).build());

        assertThat(p.getPaymentProvider()).isEqualTo(PaymentProvider.BANK_TRANSFER);
        assertThat(p.getProviderPaymentId()).isNull();
    }

    /** 옛 PayPal 전용 컬럼은 더 매핑하지 않는다 — 다음 릴리스에서 컬럼을 지운 뒤 다시 매핑하면 기동하지 못한다. */
    @Test
    @DisplayName("결제 엔티티는 옛 paypal_order_id 컬럼을 매핑하지 않는다")
    void paymentNoLongerMapsTheLegacyColumn() {
        assertThat(java.util.Arrays.stream(Payment.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("paypalOrderId");
    }
}
