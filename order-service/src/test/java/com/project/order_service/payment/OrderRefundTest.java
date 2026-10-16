package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.dto.request.OrderItemQtyRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;
import com.project.order_service.service.OrderDataService;

/**
 * 환불 규칙 — {@code OrderDataService.refundOrder}.
 *
 * <p>결제 확인({@link PaypalPaymentVerificationTest})의 거울상이다. 여기 고정하는 규칙은
 * 게이트웨이 <b>바깥</b>에 있으므로, 어느 제공자든 같은 규칙을 탄다. 게이트웨이는 가짜다 —
 * "제공자가 뭐라고 답했는가" 만 조작한다.
 */
@DisplayName("환불 규칙")
class OrderRefundTest {

    private static final String TRACKING_ID = "track-1";
    private static final BigDecimal ORDER_TOTAL = new BigDecimal("5998.00");

    private OrderRepository orderRepository;
    private PaymentOutboxHelper outboxHelper;
    private RefundingGateway gateway;
    private OrderDataService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        outboxHelper = mock(PaymentOutboxHelper.class);
        gateway = mock(RefundingGateway.class);
        when(gateway.provider()).thenReturn(PaymentProvider.STRIPE);

        order = Order.builder()
                .orderId(1L)
                .trackingId(TRACKING_ID)
                .customerId(7L)
                .total(ORDER_TOTAL)
                .totalBeforeDiscount(ORDER_TOTAL)
                .couponApplied("SUMMER")
                .orderStatus(OrderStatus.PAID)
                .paymentProvider(PaymentProvider.STRIPE)
                .providerPaymentId("cs_test_1")
                .build();
        // 품목 둘: 2 × 1999.00 + 1 × 2000.00 = 5998.00 — 부분 환불의 단위다.
        order.setOrderedProducts(new java.util.ArrayList<>(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L)
                        .price(new BigDecimal("1999.00")).order(order).build(),
                OrderProduct.builder().orderProductId(102L).productId(11L).qty(1).size("L").colorId(6L)
                        .price(new BigDecimal("2000.00")).order(order).build())));

        when(orderRepository.findByTrackingId(TRACKING_ID)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));


        service = new OrderDataService(
                orderRepository, new ObjectMapper(), mock(OrderAddressRepository.class), outboxHelper,
                new PaymentGateways(List.of(gateway)), testPolicy());
        // 서비스는 "취소 직전 잔액" 을 싣는 6인자 판을 부른다. 이 목의 기본 메서드는 실제처럼 5인자 판으로 넘긴다 — 아래 검증들이
        // 5인자 판을 본다(잔액을 쓰는 것은 포트원 어댑터뿐이다).
        when(gateway.refund(any(), any(), any(), any(), any(), any())).thenCallRealMethod();
    }

    /** 테스트 통화 정책 — 상점 USD, PayPal USD, Stripe JPY(150), 포트원 KRW(1350). */
    static PaymentCurrencyPolicy testPolicy() {
        PaymentCurrencyPolicy p = new PaymentCurrencyPolicy();
        p.setBaseCurrency("USD");
        p.setProviders(java.util.Map.of("PAYPAL", "USD", "STRIPE", "JPY", "PORTONE", "KRW"));
        p.setRates(java.util.Map.of("JPY", new BigDecimal("150"), "KRW", new BigDecimal("1350")));
        return p;
    }

    private void providerSays(boolean accepted, String amount, String currency) {
        when(gateway.refund(anyString(), any(), anyString(), any(), any()))
                .thenReturn(new RefundResult(PaymentProvider.STRIPE, "re_1", accepted, accepted ? "succeeded" : "failed",
                        amount == null ? null : new BigDecimal(amount), currency));
    }

    private Order refundAsCustomer() {
        return service.refundOrder(TRACKING_ID, 7L, false, "단순 변심");
    }

    // ---------------------------------------------------------------- 정상

    /**
     * 🔒 취소 직전 잔액(청구 통화, 우리 기록)을 어댑터에 넘긴다 — 멱등 헤더가 없는 포트원이 이것으로 같은 환불을 두 번 내지 않는다.
     * 첫 환불이면 청구액 전부다.
     */
    @Test
    @DisplayName("🔒 어댑터에 취소 직전 잔액(청구 통화)을 넘긴다")
    void passesRefundableBeforeToTheGateway() {
        providerSays(true, "5998", "USD");

        refundAsCustomer();

        verify(gateway).refund(eq("cs_test_1"), eq(ORDER_TOTAL), eq("USD"), eq("단순 변심"), any(), eq(ORDER_TOTAL));
    }

    @Test
    @DisplayName("제공자가 받아들이면 주문이 REFUNDED 가 되고 환불 참조가 남는다")
    void acceptedRefundIsRecorded() {
        providerSays(true, "5998", "USD");

        Order refunded = refundAsCustomer();

        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefundId()).isEqualTo("re_1");
        assertThat(refunded.getRefundedAt()).isNotNull();
    }

    /** 🔒 환불액은 요청 본문이 아니라 DB 의 주문 총액이다. 게이트웨이가 받은 값을 본다. */
    @Test
    @DisplayName("🔒 제공자에게 요청하는 환불액은 DB 의 주문 총액이다")
    void refundAmountComesFromTheOrder() {
        providerSays(true, "5998", "USD");

        refundAsCustomer();

        verify(gateway).refund(eq("cs_test_1"), eq(ORDER_TOTAL), eq("USD"), eq("단순 변심"), any());
    }

    @Test
    @DisplayName("pay-service 에 CANCELED 를 같은 아웃박스로 알린다")
    void outboxCarriesCanceled() {
        providerSays(true, "5998", "USD");

        refundAsCustomer();

        ArgumentCaptor<PaymentRequest> captor = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(outboxHelper).savePaymentOutbox(captor.capture(), eq(OutboxStatus.STARTED));
        assertThat(captor.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(captor.getValue().getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(captor.getValue().getTrackingId()).isEqualTo(TRACKING_ID);
    }

    /** 🔴 KRW 로 받았으면 KRW 로 돌려준다 — 상점 통화(USD)의 total 이 아니다. */
    @Test
    @DisplayName("🔴 청구 통화로 환불한다 — KRW 로 받은 주문은 KRW 청구액을 돌려준다")
    void refundsInChargedCurrency() {
        order.setCurrency("KRW");
        order.setChargedAmount(new BigDecimal("8097300"));   // 5998 × 1350
        when(gateway.refund(anyString(), any(), anyString(), any(), any()))
                .thenReturn(new RefundResult(PaymentProvider.STRIPE, "re_krw", true, "succeeded",
                        new BigDecimal("8097300"), "KRW"));

        Order refunded = refundAsCustomer();

        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(gateway).refund(eq("cs_test_1"), eq(new BigDecimal("8097300")), eq("KRW"), any(), any());
    }

    // ---------------------------------------------------------------- 거절 — 제공자를 부르기 전

    @Test
    @DisplayName("🔒 남의 주문은 환불할 수 없다 — 404 와 같은 문구")
    void strangerCannotRefund() {
        assertThatThrownBy(() -> service.refundOrder(TRACKING_ID, 99L, false, null))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("찾을 수 없습니다");
        verify(gateway, never()).refund(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("관리자는 남의 주문도 환불할 수 있다")
    void adminMayRefundAnyOrder() {
        providerSays(true, "5998", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, 99L, true, "고객센터 접수");

        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("결제되지 않은 주문은 환불이 아니라 취소의 영역이다")
    void unpaidOrderIsRejected() {
        order.setOrderStatus(OrderStatus.NOT_PROCESSED);
        order.setProviderPaymentId(null);

        assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("결제되지 않은");
        verify(gateway, never()).refund(any(), any(), any(), any(), any());
    }

    /** 발송된 뒤에는 물건이 돌아와야 한다 — 사용자 스스로는 안 되고 관리자가 판단한다. */
    @Test
    @DisplayName("발송된 주문은 사용자가 스스로 환불할 수 없다 — 관리자는 된다")
    void dispatchedOrderNeedsAdmin() {
        order.setOrderStatus(OrderStatus.DISPATCHED);

        assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("배송이 시작된");
        verify(gateway, never()).refund(any(), any(), any(), any(), any());

        providerSays(true, "5998", "USD");
        Order refunded = service.refundOrder(TRACKING_ID, 7L, true, null);
        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    // ---------------------------------------------------------------- 반품·재고

    /** 발송 전 환불은 물건이 창고에 그대로다 — 기다릴 반품이 없고, 재고는 바로 돌아온다(OrderSaga). */
    @Test
    @DisplayName("발송 전 환불은 반품 대기가 아니다")
    void refundBeforeDispatchDoesNotAwaitReturn() {
        providerSays(true, "5998", "USD");

        assertThat(refundAsCustomer().isAwaitingReturn()).isFalse();
    }

    /** 발송된 뒤의 환불은 물건이 밖에 있다 — 입고될 때까지 재고를 돌려주지 않는다. */
    @Test
    @DisplayName("발송된 주문의 환불은 반품 대기다 — 재고는 입고 때 돌아온다")
    void refundAfterDispatchAwaitsReturn() {
        order.setOrderStatus(OrderStatus.DELIVERED);
        providerSays(true, "5998", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, 7L, true, null);

        assertThat(refunded.isAwaitingReturn()).isTrue();
    }

    /** 물건이 먼저 돌아온 뒤 환불하면 기다릴 것이 없다. */
    @Test
    @DisplayName("입고가 먼저 기록된 주문의 환불은 반품 대기가 아니다")
    void refundAfterReturnAlreadyReceived() {
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setReturnReceivedAt(java.time.LocalDateTime.now().minusDays(1));
        order.getOrderedProducts().forEach(p -> p.setReturnedQty(p.getQty()));   // 입고가 세어 둔 것
        providerSays(true, "5998", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, 7L, true, null);

        assertThat(refunded.isAwaitingReturn()).isFalse();
    }

    /** 🔴 이미 환불된 주문에 또 오면 제공자를 **다시 부르지 않는다** — 두 번 환불되면 안 된다. */
    @Test
    @DisplayName("🔴 이미 환불된 주문은 제공자를 다시 부르지 않는다 (멱등)")
    void alreadyRefundedIsIdempotent() {
        order.setOrderStatus(OrderStatus.REFUNDED);
        order.setRefundId("re_old");

        Order result = refundAsCustomer();

        assertThat(result.getRefundId()).isEqualTo("re_old");
        verify(gateway, never()).refund(any(), any(), any(), any(), any());
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    // ---------------------------------------------------------------- 부분 환불

    private static List<OrderItemQtyRequest> items(Object... pairs) {
        List<OrderItemQtyRequest> out = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new OrderItemQtyRequest((Long) pairs[i], (Integer) pairs[i + 1]));
        }
        return out;
    }

    /** 🔒 금액은 서버가 정한다 — 품목 단가 × 수량. 요청 본문에는 금액이 없다. */
    @Test
    @DisplayName("🔒 일부 품목만 환불하면 그 단가만큼만 돌려주고 주문은 결제 상태로 남는다")
    void partialRefundKeepsTheOrderPaid() {
        providerSays(true, "1999.00", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, 7L, false, "하나 반품",
                items(101L, 1));

        verify(gateway).refund(eq("cs_test_1"), eq(new BigDecimal("1999.00")), eq("USD"), any(), any());
        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(refunded.getRefundedAmount()).isEqualByComparingTo("1999.00");
        assertThat(refunded.getOrderedProducts().get(0).getRefundedQty()).isEqualTo(1);
        assertThat(refunded.getOrderedProducts().get(0).getReturnedQty())
                .as("발송 전이라 창고에 그대로 — 돌아온 것으로 센다").isEqualTo(1);
        assertThat(refunded.getRefunds()).hasSize(1);
        assertThat(refunded.getRefunds().get(0).getItems()).contains("101");
        // 결제가 아직 살아 있다 — pay-service 에 CANCELED 를 보내지 않는다.
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    // ---------------------------------------------------------------- 재고 부족분 (docs/53)

    /**
     * 🔴 재고가 모자라 배정되지 못한 수는 차감된 적이 없다 — 환불해도 "돌아왔다" 로 세면 재고 복원이 없는 재고를
     * 만든다. 환불은 부족분을 먼저 소진하고, 그다음부터가 창고에 있는 몫이다.
     */
    @Test
    @DisplayName("🔴 재고 부족분 환불은 돌아온 수로 세지 않는다 — 없는 재고를 복원하지 않게")
    void shortUnitsAreNotReturned() {
        OrderProduct line = order.getOrderedProducts().get(0);   // 2개 중
        line.setShortQty(1);                                     // 1개는 배정되지 못했다
        providerSays(true, "1999.00", "USD");

        service.refundOrder(TRACKING_ID, null, true, "재고 부족", items(101L, 1));

        assertThat(line.getRefundedQty()).isEqualTo(1);
        assertThat(line.getReturnedQty()).as("부족분 — 창고에 없던 것").isZero();
        assertThat(line.pendingRestock()).isZero();

        service.refundOrder(TRACKING_ID, 7L, false, "나머지도", items(101L, 1));

        assertThat(line.getReturnedQty()).as("배정된 몫 — 창고에 있다").isEqualTo(1);
        assertThat(line.pendingRestock()).isEqualTo(1);
    }

    @Test
    @DisplayName("발송된 뒤 부족분만 환불했으면 반품 대기가 아니다 — 나간 적이 없다")
    void shortRefundAfterDispatchDoesNotAwaitReturn() {
        order.setOrderStatus(OrderStatus.DISPATCHED);
        order.getOrderedProducts().get(0).setShortQty(1);
        providerSays(true, "1999.00", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, null, true, "재고 부족", items(101L, 1));

        assertThat(refunded.isAwaitingReturn()).isFalse();
        assertThat(refunded.getOrderedProducts().get(0).remainingToReturn()).as("배정된 1개만 돌아올 수 있다").isEqualTo(1);
    }

    /** 마지막 환불은 남은 전부다 — 부분 환불의 합은 정확히 총액이고 그때 REFUNDED 가 된다. */
    @Test
    @DisplayName("남은 수량을 전부 환불하면 남은 금액 전부가 나가고 REFUNDED 가 된다")
    void lastPartialRefundCompletesTheOrder() {
        providerSays(true, "1999.00", "USD");
        service.refundOrder(TRACKING_ID, 7L, false, null, items(101L, 1));

        providerSays(true, "3999.00", "USD");
        Order refunded = service.refundOrder(TRACKING_ID, 7L, false, null, null);

        verify(gateway).refund(eq("cs_test_1"), eq(new BigDecimal("3999.00")), eq("USD"), any(), any());
        assertThat(refunded.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefundedAmount()).isEqualByComparingTo(ORDER_TOTAL);
        assertThat(refunded.getRefunds()).hasSize(2);
        verify(outboxHelper).savePaymentOutbox(any(), eq(OutboxStatus.STARTED));
    }

    @Test
    @DisplayName("남은 수량을 넘는 요청은 제공자를 부르기 전에 거절한다")
    void overRefundIsRejectedBeforeTheProvider() {
        assertThatThrownBy(() -> service.refundOrder(TRACKING_ID, 7L, false, null, items(102L, 2)))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("남은 수량");
        assertThatThrownBy(() -> service.refundOrder(TRACKING_ID, 7L, false, null, items(999L, 1)))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("이 주문의 품목이 아닙니다");
        verify(gateway, never()).refund(any(), any(), any(), any(), any());
    }

    /** 🔴 같은 캡처에 환불이 여럿이면 멱등 키가 달라야 한다 — 같으면 두 번째가 첫 번째의 응답이 된다. */
    @Test
    @DisplayName("🔴 부분 환불마다 멱등 키가 다르다")
    void eachPartialRefundHasItsOwnKey() {
        providerSays(true, "1999.00", "USD");
        service.refundOrder(TRACKING_ID, 7L, false, null, items(101L, 1));
        providerSays(true, "1999.00", "USD");
        service.refundOrder(TRACKING_ID, 7L, false, null, items(101L, 1));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.times(2)).refund(anyString(), any(), anyString(), any(), keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotBlank().isNotEqualTo(keys.getAllValues().get(1));
    }

    /** 발송된 뒤 일부만 환불하면 그 수량만 반품 대기다. */
    @Test
    @DisplayName("발송된 뒤의 부분 환불은 반품 대기 — 돌아온 수량이 환불 수량보다 적다")
    void partialRefundAfterDispatchAwaitsReturn() {
        order.setOrderStatus(OrderStatus.DELIVERED);
        providerSays(true, "2000.00", "USD");

        Order refunded = service.refundOrder(TRACKING_ID, 7L, true, null, items(102L, 1));

        assertThat(refunded.isAwaitingReturn()).isTrue();
        assertThat(refunded.getOrderedProducts().get(1).getReturnedQty()).isZero();
    }

    // ---------------------------------------------------------------- 거절 — 제공자 응답

    @Test
    @DisplayName("제공자가 거절하면 주문은 그대로다")
    void providerRejectionLeavesOrderPaid() {
        providerSays(false, null, null);

        assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("받아들이지 않았습니다");
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        verify(outboxHelper, never()).savePaymentOutbox(any(), any());
    }

    /** 돈은 이미 나갔다. 거부하되 운영자가 찾을 수 있게 환불 참조를 예외에 싣는다. */
    @Test
    @DisplayName("환불액이 주문 총액과 다르면 거부하고 환불 참조를 남긴다")
    void amountMismatchIsRejectedWithReference() {
        providerSays(true, "1000", "USD");

        assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("환불액이 주문 총액과 다릅니다")
                .hasMessageContaining("re_1");
    }

    @Test
    @DisplayName("통화가 다르면 거부한다")
    void currencyMismatchIsRejected() {
        providerSays(true, "5998", "KRW");

        assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("통화");
    }

    @Test
    @DisplayName("환불 API 가 없는 제공자면 이유를 달고 거부한다")
    void nonRefundingProviderIsRejected() {
        PaymentGateway plain = mock(PaymentGateway.class);
        when(plain.provider()).thenReturn(PaymentProvider.PAYPAL);
        PaymentGateways gateways = new PaymentGateways(List.of(plain));

        assertThatThrownBy(() -> gateways.requireRefunding(PaymentProvider.PAYPAL))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("자동 환불");
    }

    @Test
    @DisplayName("사유는 줄바꿈·따옴표를 빼고 200자로 자른다 — 제공자 영수증에 남는 문구다")
    void reasonIsSanitized() {
        assertThat(OrderDataService.sanitizeReason("a\"b\nc")).isEqualTo("a b c");
        assertThat(OrderDataService.sanitizeReason(null)).isEmpty();
        assertThat(OrderDataService.sanitizeReason("x".repeat(500))).hasSize(200);
    }

    // ---------------------------------------------------------------- 동시 환불

    /**
     * 🔴 같은 주문의 환불 둘이 겹치면 — 예전에는 둘 다 제공자에게 돈을 돌려주게 한 뒤 늦은 커밋만 낙관적 잠금으로 실패했다(돈은 두 번,
     * 기록은 한 번). 이제 행을 잠그고, 읽은 뒤에 다른 요청이 바꿨으면 제공자를 부르기 전에 멈춘다.
     */
    @Test
    @DisplayName("🔴 읽은 뒤 다른 요청이 주문을 바꿨으면(버전이 다르면) 제공자를 부르지 않고 거절한다")
    void concurrentChangeStopsBeforeTheProvider() {
        org.springframework.test.util.ReflectionTestUtils.setField(order, "version", 3L);
        when(orderRepository.lockVersion(1L)).thenReturn(4L);
        providerSays(true, "5998", "USD");

        org.assertj.core.api.Assertions.assertThatThrownBy(this::refundAsCustomer)
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessage(OrderDataService.CONCURRENT_CHANGE_MESSAGE);
        verify(gateway, never()).refund(anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("🔴 환불은 주문 행을 잠그고 시작한다 — 버전이 같으면 그대로 진행")
    void refundLocksTheOrderRow() {
        org.springframework.test.util.ReflectionTestUtils.setField(order, "version", 3L);
        when(orderRepository.lockVersion(1L)).thenReturn(3L);
        providerSays(true, "5998", "USD");

        refundAsCustomer();

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orderRepository, gateway);
        inOrder.verify(orderRepository).lockVersion(1L);
        inOrder.verify(gateway).refund(anyString(), any(), anyString(), any(), any());
    }
}
