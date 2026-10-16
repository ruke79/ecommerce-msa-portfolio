package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.client.CartServiceClient;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;
import com.project.order_service.service.OrderDataService;

/**
 * 결제 검증 회귀 테스트 — docs/35.
 *
 * <p>고치기 전에는 이 경로가 <b>요청 본문을 그대로 믿었다.</b> 금액도 결제 상태도 브라우저가
 * 정했고 서버는 아무것도 확인하지 않았다. 즉 결제하지 않고 curl 한 번으로 주문을 완료시킬 수
 * 있었다. 아래 테스트들은 그 각각의 공격이 이제 막히는지를 본다.
 *
 * <p>가장 중요한 것은 <b>실패할 때 아웃박스에 아무것도 들어가지 않는다</b>는 확인이다.
 * 예외를 던지더라도 그 전에 아웃박스에 넣었다면 결제는 이미 하류로 전파된 뒤다.
 */
class PaypalPaymentVerificationTest {

    private static final String TRACKING_ID = "track-1";
    private static final String PAYPAL_ORDER_ID = "PP-REAL-1";
    /** 주문 총액 — 상점 통화, 소수 둘째 자리까지(V7). */
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("5998.00");

    private OrderRepository orderRepository;
    private PaymentOutboxHelper outboxHelper;
    private PaypalOrderVerifier verifier;
    private OrderDataService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxHelper = mock(PaymentOutboxHelper.class);
        verifier = mock(PaypalOrderVerifier.class);

        order = Order.builder()
                .orderId(1L)
                .trackingId(TRACKING_ID)
                .customerId(7L)
                .total(ORDER_TOTAL)
                .couponApplied("SUMMER")
                .orderStatus(OrderStatus.NOT_PROCESSED)
                .build();

        when(orderRepository.findByTrackingId(TRACKING_ID)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));


        service = new OrderDataService(
                orderRepository,
                new ObjectMapper(),
                mock(OrderAddressRepository.class),
                outboxHelper,
                // 포트가 생기면서 검증기는 게이트웨이 뒤로 갔다. 이 테스트가 고정하는
                // 규칙(금액·통화·재사용·아웃박스)은 게이트웨이 **바깥**에 있으므로
                // 어댑터를 그대로 끼워 예전과 같은 것을 검증한다.
                new PaymentGateways(java.util.List.of(new PaypalPaymentGateway(verifier))),
                testPolicy());
    }

    /** 테스트 통화 정책 — 상점 USD, PayPal USD, Stripe JPY(150), 포트원 KRW(1350). */
    static PaymentCurrencyPolicy testPolicy() {
        PaymentCurrencyPolicy p = new PaymentCurrencyPolicy();
        p.setBaseCurrency("USD");
        p.setProviders(java.util.Map.of("PAYPAL", "USD", "STRIPE", "JPY", "PORTONE", "KRW"));
        p.setRates(java.util.Map.of("JPY", new BigDecimal("150"), "KRW", new BigDecimal("1350")));
        return p;
    }


    /**
     * 결제를 반영한다.
     *
     * <p>예전에는 {@code persisitPaypalPayment(request, token)} 이었고 요청 본문에 금액·
     * 결제 상태가 들어 있었다. 이제 서버가 읽는 것은 <b>어느 주문의 어떤 결제 참조</b>
     * 둘뿐이다 — 나머지는 애초에 전달되지 않으므로 "본문을 믿지 않는다" 가 시그니처에
     * 드러난다.
     */
    private void record(String paypalOrderId) {
        service.recordPayment(TRACKING_ID, PaymentProvider.PAYPAL, paypalOrderId, 7L);
    }

    /** 브라우저가 보내는 요청. 금액·상태는 <b>공격자가 마음대로 정할 수 있는 값</b>이다. */
    private PaypalPaymentRequest request(String paypalOrderId, BigDecimal claimedAmount) {
        PaypalPaymentRequest r = new PaypalPaymentRequest();
        r.setTrackingId(TRACKING_ID);
        r.setPaypalOrderId(paypalOrderId);
        r.setUserEmail("buyer@example.com");
        r.setAmounts(claimedAmount);
        r.setPaymentStatus("COMPLETED");
        r.setOrderStatus("NOT_PROCESSED");
        return r;
    }

    private void paypalSays(String status, String amount, String currency) {
        when(verifier.verify(any())).thenReturn(new VerifiedPaypalOrder(
                PAYPAL_ORDER_ID, status,
                amount == null ? null : new BigDecimal(amount), currency));
    }

    // ---------------------------------------------------------------- 정상

    @Test
    @DisplayName("PayPal 이 확인해 주면 결제가 반영된다")
    void verifiedPaymentIsPersisted() {
        paypalSays("COMPLETED", "5998", "USD");

        record(PAYPAL_ORDER_ID);

        var captured = org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);
        verify(outboxHelper).savePaymentOutbox(captured.capture(), any(OutboxStatus.class));
        assertThat(captured.getValue().getProviderPaymentId()).isEqualTo(PAYPAL_ORDER_ID);
        assertThat(captured.getValue().getPaymentProvider()).isEqualTo(PaymentProvider.PAYPAL);
        assertThat(captured.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        // 재사용 차단을 위해 주문에 기록돼야 한다 — (payment_provider, provider_payment_id) 유니크(V4).
        assertThat(order.getPaymentProvider()).isEqualTo(PaymentProvider.PAYPAL);
        assertThat(order.getProviderPaymentId()).isEqualTo(PAYPAL_ORDER_ID);
    }

    @Test
    @DisplayName("페이로드의 금액은 요청 본문이 아니라 PayPal 이 확인해 준 값이다")
    void amountComesFromPaypalNotFromRequest() {
        paypalSays("COMPLETED", "5998", "USD");

        // 요청 본문은 1원이라고 주장한다. 서버는 무시해야 한다.
        record(PAYPAL_ORDER_ID);

        var captured = org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);
        verify(outboxHelper).savePaymentOutbox(captured.capture(), any(OutboxStatus.class));
        assertThat(captured.getValue().getAmounts()).isEqualByComparingTo("5998");
    }

    // ---------------------------------------------------------------- 통화

    /**
     * 🔴 청구 통화가 상점 통화와 다른 주문 — 일본 테스트(JPY)나 포트원(KRW).
     * 대조 기준은 <b>주문에 저장된 청구</b>(통화·금액)이지 상점 통화의 total 이 아니다.
     */
    @Test
    @DisplayName("🔴 JPY 로 청구된 주문은 JPY 청구액과 대조한다 — USD total 이 아니다")
    void chargedInJpyIsCheckedAgainstJpy() {
        order.setCurrency("JPY");
        order.setChargedAmount(new BigDecimal("899700"));   // 5998 × 150
        order.setFxRate(new BigDecimal("150"));

        // PayPal 이 USD 5998 이라고 답하면 — 금액 숫자는 같아도 통화가 다르다 — 거절
        paypalSays("COMPLETED", "5998", "USD");
        assertThatThrownBy(() -> record(PAYPAL_ORDER_ID))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("청구액과 다릅니다");

        // JPY 899700 이면 통과
        paypalSays("COMPLETED", "899700", "JPY");
        assertThatCode(() -> record(PAYPAL_ORDER_ID)).doesNotThrowAnyException();
    }

    /** 하류(pay-service · 매출)는 상점 통화로 산다 — 청구 통화의 금액을 섞지 않는다. */
    @Test
    @DisplayName("아웃박스의 금액은 청구 통화가 아니라 상점 통화의 총액이다")
    void outboxCarriesBaseCurrencyTotal() {
        order.setCurrency("JPY");
        order.setChargedAmount(new BigDecimal("899700"));
        paypalSays("COMPLETED", "899700", "JPY");

        record(PAYPAL_ORDER_ID);

        var captured = org.mockito.ArgumentCaptor.forClass(PaymentRequest.class);
        verify(outboxHelper).savePaymentOutbox(captured.capture(), any(OutboxStatus.class));
        assertThat(captured.getValue().getAmounts()).isEqualByComparingTo("5998");
    }

    /** 이 컬럼이 생기기 전 주문(currency null)은 예전 그대로 상점 통화의 total 과 대조한다. */
    @Test
    @DisplayName("청구 통화가 없는 옛 주문은 상점 통화로 total 과 대조한다")
    void legacyOrderFallsBackToBase() {
        order.setCurrency(null);
        paypalSays("COMPLETED", "5998", "USD");
        assertThatCode(() -> record(PAYPAL_ORDER_ID)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 공격

    @Test
    @DisplayName("🔒 결제하지 않고 지어낸 주문 ID 는 거부된다")
    void fabricatedOrderIdIsRejected() {
        // PayPal 이 "그런 주문 없다"고 답하는 상황.
        when(verifier.verify(any()))
                .thenThrow(new PaypalVerificationException("PayPal 주문 조회 실패"));

        assertThatThrownBy(() ->
                record("아무거나"))
                .isInstanceOf(PaymentVerificationException.class);

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("🔒 승인만 되고 캡처되지 않은 결제는 거부된다")
    void approvedButNotCapturedIsRejected() {
        // APPROVED 는 돈이 오지 않은 상태다. 여기서 통과시키면 무료로 물건이 나간다.
        paypalSays("APPROVED", "5998", "USD");

        assertThatThrownBy(() ->
                record(PAYPAL_ORDER_ID))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("APPROVED");

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("🔒 실제 결제 금액이 주문 총액보다 적으면 거부된다")
    void underpaymentIsRejected() {
        // 59.98 짜리 주문을 0.01 만 결제한 뒤 그 주문 ID 를 붙이는 공격.
        paypalSays("COMPLETED", "1", "USD");

        assertThatThrownBy(() ->
                record(PAYPAL_ORDER_ID))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("금액");

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("🔒 통화가 다르면 거부된다 — 금액만 비교하면 5998 USD 를 5998 KRW 로 바꿀 수 있다")
    void currencyMismatchIsRejected() {
        paypalSays("COMPLETED", "5998", "KRW");

        assertThatThrownBy(() ->
                record(PAYPAL_ORDER_ID))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("통화");

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("🔒 다른 주문에 이미 쓴 PayPal 결제는 재사용할 수 없다")
    void replayAcrossOrdersIsRejected() {
        paypalSays("COMPLETED", "5998", "USD");
        // DB 의 부분 유니크 인덱스가 거부하는 상황.
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("uk_order_provider_payment"));

        assertThatThrownBy(() ->
                record(PAYPAL_ORDER_ID))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("이미 다른 주문");

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 주문에 결제를 붙이면 거부된다 — 예전에는 조용히 200 이었다")
    void unknownOrderIsRejected() {
        when(orderRepository.findByTrackingId("없는-주문")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordPayment("없는-주문", PaymentProvider.PAYPAL,
                PAYPAL_ORDER_ID, 7L))
                .isInstanceOf(PaymentVerificationException.class);

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    // ---------------------------------------------------------------- 중복 요청

    @Test
    @DisplayName("같은 주문에 같은 결제를 다시 보내면 조용히 넘어간다 (새로고침)")
    void sameOrderSamePaymentIsIdempotent() {
        paypalSays("COMPLETED", "5998", "USD");
        order.setPaymentProvider(PaymentProvider.PAYPAL);
        order.setProviderPaymentId(PAYPAL_ORDER_ID);   // 이미 반영된 상태

        assertThatCode(() ->
                record(PAYPAL_ORDER_ID))
                .doesNotThrowAnyException();

        // 두 번 발행하면 하류에서 결제가 두 건이 된다.
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    /**
     * 같은 결제가 다시 오면(새로고침·웹훅과 복귀 검증이 겹친다) 한 번만 반영한다 — 두 번 발행하면 하류에서 결제가 두 건이
     * 된다. 예전에는 옛 컬럼(paypal_order_id)에만 기록된 결제도 여기서 함께 봤다. 그 값은 V17 이 새 컬럼으로 옮겼다.
     */
    @Test
    @DisplayName("이미 반영된 결제가 다시 오면 발행하지 않는다")
    void aPaymentAlreadyRecordedIsIdempotent() {
        paypalSays("COMPLETED", "5998", "USD");
        order.setPaymentProvider(PaymentProvider.PAYPAL);
        order.setProviderPaymentId(PAYPAL_ORDER_ID);

        assertThatCode(() -> record(PAYPAL_ORDER_ID)).doesNotThrowAnyException();

        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    /**
     * 옛 PayPal 전용 컬럼은 더 매핑하지 않는다. 다시 매핑하면 그 컬럼을 지우는 다음 마이그레이션 뒤 Hibernate validate
     * 에서 기동하지 못한다(docs/49 §3).
     */
    @Test
    @DisplayName("주문 엔티티는 옛 paypal_order_id 컬럼을 매핑하지 않는다")
    void orderNoLongerMapsTheLegacyColumn() {
        assertThat(java.util.Arrays.stream(Order.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("paypalOrderId");
    }

    // ---------------------------------------------------------------- 검증 해제 모드

    @Test
    @DisplayName("검증을 끈 모드에서는 금액 대조를 건너뛴다 (로컬 개발 전용)")
    void disabledModeSkipsAmountCheck() {
        PaypalOrderVerifier disabled = new DisabledPaypalOrderVerifier();
        VerifiedPaypalOrder v = disabled.verify(PAYPAL_ORDER_ID);

        assertThat(v.isCompleted()).isTrue();
        // amount 가 null 이라는 것이 '검증하지 않았다'는 신호다.
        assertThat(v.amount()).isNull();
    }

    // ---------------------------------------------------------------- 이중 결제

    /**
     * 🔴 이미 결제가 붙은 주문에 다른 결제가 확인되면 — 예전에는 결제 참조를 두 번째 것으로 덮어써 첫 결제의 참조가 사라졌다(환불도 두
     * 번째 것만). 이제 덮지 않고 거절하며 세어 경보한다(OrderDuplicatePayment).
     */
    @Test
    @DisplayName("🔴 이미 다른 결제가 붙은 주문의 두 번째 결제는 덮어쓰지 않고 거절하며 센다")
    void secondPaymentIsRejectedAndCounted() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(meters);
        try {
            order.setPaymentProvider(PaymentProvider.PAYPAL);
            order.setProviderPaymentId("FIRST-PAYMENT");
            paypalSays("COMPLETED", "5998", "USD");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> record(PAYPAL_ORDER_ID))
                    .isInstanceOf(com.project.order_service.payment.PaymentVerificationException.class)
                    .hasMessage(OrderDataService.DUPLICATE_PAYMENT_MESSAGE);

            assertThat(order.getProviderPaymentId()).as("첫 결제의 참조가 남는다").isEqualTo("FIRST-PAYMENT");
            verify(outboxHelper, org.mockito.Mockito.never()).savePaymentOutbox(any(), any());
            assertThat(meters.counter(OrderDataService.DUPLICATE_PAYMENT_METRIC, "provider", "PAYPAL").count()).isEqualTo(1);
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(meters);
        }
    }

    @Test
    @DisplayName("🔴 결제 기록은 주문 행을 잠그고 시작한다 — 읽은 뒤 다른 요청이 바꿨으면 거절(겹친 두 결제의 늦은 쪽이 이중 결제 검사를 본다)")
    void recordPaymentLocksTheOrder() {
        org.springframework.test.util.ReflectionTestUtils.setField(order, "version", 1L);
        when(orderRepository.lockVersion(1L)).thenReturn(2L);
        paypalSays("COMPLETED", "5998", "USD");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> record(PAYPAL_ORDER_ID))
                .isInstanceOf(com.project.order_service.payment.PaymentVerificationException.class)
                .hasMessage(OrderDataService.CONCURRENT_CHANGE_MESSAGE);
        verify(verifier, org.mockito.Mockito.never()).verify(any());
    }

    @Test
    @DisplayName("🔴 겹친 두 결제의 늦은 쪽은 잠근 채 다시 읽어 이중 결제 검사에 걸린다 — 예전에는 경보 없이 '처리 중' 으로 거절했다")
    void lateConcurrentPaymentIsCountedAsDuplicate() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(meters);
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(order, "version", 1L);
            when(orderRepository.lockVersion(1L)).thenReturn(2L);
            jakarta.persistence.EntityManager em = mock(jakarta.persistence.EntityManager.class);
            // 다시 읽으면 앞선 결제가 보인다(그 커밋이 버전을 올렸다).
            org.mockito.Mockito.doAnswer(i -> {
                order.setPaymentProvider(PaymentProvider.PAYPAL);
                order.setProviderPaymentId("FIRST-PAYMENT");
                return null;
            }).when(em).refresh(order);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);
            paypalSays("COMPLETED", "5998", "USD");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> record(PAYPAL_ORDER_ID))
                    .hasMessage(OrderDataService.DUPLICATE_PAYMENT_MESSAGE);
            verify(em).refresh(order);
            assertThat(order.getProviderPaymentId()).isEqualTo("FIRST-PAYMENT");
            assertThat(meters.counter(OrderDataService.DUPLICATE_PAYMENT_METRIC, "provider", "PAYPAL").count()).isEqualTo(1);
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(meters);
        }
    }

    // ---------------------------------------------------------------- 주문 참조 대조

    private OrderDataService serviceWith(VerifiedPayment verified) {
        PaymentGateway gateway = new PaymentGateway() {
            @Override
            public PaymentProvider provider() {
                return PaymentProvider.STRIPE;
            }

            @Override
            public VerifiedPayment verify(String providerPaymentId) {
                return verified;
            }
        };
        return new OrderDataService(orderRepository, new ObjectMapper(),
                mock(OrderAddressRepository.class), outboxHelper, new PaymentGateways(java.util.List.of(gateway)), testPolicy());
    }

    /** 🔴 같은 금액의 다른 주문(남의 것일 수도 있다)의 결제로 이 주문을 결제 완료로 만들 수 없다 — 예전에는 금액 · 통화만 봤다. */
    @Test
    @DisplayName("🔴 제공자가 붙여 둔 주문 참조가 이 주문이 아니면 거절한다")
    void paymentForAnotherOrderIsRejected() {
        OrderDataService stripe = serviceWith(new VerifiedPayment(PaymentProvider.STRIPE, "cs_other", true, "paid",
                new BigDecimal("5998"), "USD", "someone-elses-order"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stripe.recordPayment(TRACKING_ID, PaymentProvider.STRIPE, "cs_other", 7L))
                .isInstanceOf(PaymentVerificationException.class).hasMessage(OrderDataService.FOREIGN_PAYMENT_MESSAGE);
        assertThat(order.getProviderPaymentId()).isNull();
        verify(outboxHelper, org.mockito.Mockito.never()).savePaymentOutbox(any(), any());
    }

    @Test
    @DisplayName("주문 참조가 이 주문이면 · 제공자가 참조를 모르면(null) 그대로 진행")
    void matchingOrUnknownReferenceProceeds() {
        serviceWith(new VerifiedPayment(PaymentProvider.STRIPE, "cs_mine", true, "paid", new BigDecimal("5998"), "USD", TRACKING_ID))
                .recordPayment(TRACKING_ID, PaymentProvider.STRIPE, "cs_mine", 7L);
        assertThat(order.getProviderPaymentId()).isEqualTo("cs_mine");
    }
}
