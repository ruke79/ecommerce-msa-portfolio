package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.dto.request.OrderItemQtyRequest;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.payment.PaymentCurrencyPolicy;
import com.project.order_service.payment.PaymentGateways;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 반품 입고 규칙 (docs/49 §10).
 *
 * <p>입고는 <b>물건이 나간 주문</b>에만 기록한다. 발송 전 환불은 재고가 이미 돌아왔고,
 * 결제 전 주문은 취소의 영역이다. 재고 복원 이벤트 자체는 여기서 나가지 않는다 —
 * {@code OrderSaga.restock} 이 트랜잭션 밖에서 보낸다({@code OrderSagaRestockTest}).
 */
@DisplayName("반품 입고")
class OrderReturnTest {

    private OrderRepository orderRepository;
    private OrderDataService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        order = Order.builder()
                .orderId(1L).trackingId("track-1").customerId(7L)
                .orderStatus(OrderStatus.REFUNDED).awaitingReturn(true)
                .paymentProvider(PaymentProvider.STRIPE).providerPaymentId("cs_1").refundId("re_1")
                .build();
        // 발송된 뒤 전액 환불된 주문 — 두 품목 다 환불됐고(refunded) 아직 안 돌아왔다(returned 0).
        order.setOrderedProducts(new java.util.ArrayList<>(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).refundedQty(2).order(order).build(),
                OrderProduct.builder().orderProductId(102L).productId(11L).qty(1).refundedQty(1).order(order).build())));
        when(orderRepository.findByTrackingId("track-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        service = new OrderDataService(orderRepository,
                new ObjectMapper(), mock(OrderAddressRepository.class),
                mock(PaymentOutboxHelper.class), new PaymentGateways(List.of()), new PaymentCurrencyPolicy());
    }

    @Test
    @DisplayName("반품 대기 주문을 입고하면 대기가 풀리고 시각이 남는다")
    void receivingClearsAwaitingReturn() {
        Order received = service.receiveReturn("track-1", true);

        assertThat(received.isAwaitingReturn()).isFalse();
        assertThat(received.getReturnReceivedAt()).isNotNull();
        assertThat(received.getOrderedProducts()).allSatisfy(p ->
                assertThat(p.getReturnedQty()).isEqualTo(p.getQty()));
        assertThat(received.getOrderStatus()).as("입고는 상태를 바꾸지 않는다 — 환불된 주문은 여전히 환불됨")
                .isEqualTo(OrderStatus.REFUNDED);
    }

    /** 물건이 먼저 돌아오고 환불이 나중일 수 있다. 그때 입고는 배송 완료 주문에 기록된다. */
    @Test
    @DisplayName("아직 환불되지 않은 배송 완료 주문도 입고할 수 있다")
    void deliveredButNotRefundedCanBeReceived() {
        order.setOrderStatus(OrderStatus.DELIVERED);
        order.setAwaitingReturn(false);
        order.getOrderedProducts().forEach(p -> p.setRefundedQty(0));

        Order received = service.receiveReturn("track-1", true);

        assertThat(received.getReturnReceivedAt()).isNotNull();
        assertThat(received.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("이미 입고된 주문은 멱등하다 — 다시 저장하지 않는다")
    void alreadyReceivedIsIdempotent() {
        LocalDateTime before = LocalDateTime.now().minusHours(1);
        order.setReturnReceivedAt(before);
        order.setAwaitingReturn(false);
        order.getOrderedProducts().forEach(p -> p.setReturnedQty(p.getQty()));

        Order received = service.receiveReturn("track-1", true);

        assertThat(received.getReturnReceivedAt()).isEqualTo(before);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    /** 발송 전 환불은 재고가 이미 돌아왔다 — 입고를 또 기록하면 재고가 두 번 는다. */
    @Test
    @DisplayName("🔴 발송 전에 환불된 주문은 돌아올 물건이 없다 — 이미 돌아온 것으로 세어져 있다")
    void refundedBeforeDispatchIsRejected() {
        // 발송 전 환불: 환불 때 returnedQty 가 함께 올라가 남은 수가 0 이다.
        order.setAwaitingReturn(false);
        order.getOrderedProducts().forEach(p -> p.setReturnedQty(p.getQty()));

        assertThatThrownBy(() -> service.receiveReturn("track-1", true))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("돌아올 물건이 없는");
        verify(orderRepository, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- 부분 반품

    @Test
    @DisplayName("일부만 입고하면 그 수량만 돌아오고 나머지는 여전히 반품 대기다")
    void partialReceipt() {
        Order received = service.receiveReturn("track-1", true,
                List.of(new OrderItemQtyRequest(101L, 1)));

        assertThat(received.getOrderedProducts().get(0).getReturnedQty()).isEqualTo(1);
        assertThat(received.getOrderedProducts().get(0).pendingRestock()).isEqualTo(1);
        assertThat(received.getOrderedProducts().get(1).getReturnedQty()).isZero();
        assertThat(received.isAwaitingReturn()).as("3개 환불, 1개 돌아옴").isTrue();
    }

    @Test
    @DisplayName("파손이면 돌아온 수량이 폐기로 가고 재고로는 가지 않는다")
    void discardedIsNotRestockable() {
        Order received = service.receiveReturn("track-1", false,
                List.of(new OrderItemQtyRequest(101L, 2)));

        OrderProduct line = received.getOrderedProducts().get(0);
        assertThat(line.getReturnedQty()).isEqualTo(2);
        assertThat(line.getDiscardedQty()).isEqualTo(2);
        assertThat(line.pendingRestock()).isZero();
    }

    @Test
    @DisplayName("남은 수량을 넘는 입고는 거절")
    void overReceiptIsRejected() {
        assertThatThrownBy(() -> service.receiveReturn("track-1", true,
                List.of(new OrderItemQtyRequest(102L, 2))))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("남은 수량");
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("결제 전·발송 전 주문은 거절")
    void unshippedIsRejected() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.NOT_PROCESSED, OrderStatus.PAID,
                OrderStatus.PROCESSING, OrderStatus.CANCELED}) {
            order.setOrderStatus(s);
            order.setAwaitingReturn(false);
            assertThatThrownBy(() -> service.receiveReturn("track-1", true))
                    .as(s.name()).isInstanceOf(PaymentVerificationException.class);
        }
    }

    @Test
    @DisplayName("모르는 추적번호는 거절")
    void unknownTrackingIdIsRejected() {
        assertThatThrownBy(() -> service.receiveReturn("nope", true))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }
}
