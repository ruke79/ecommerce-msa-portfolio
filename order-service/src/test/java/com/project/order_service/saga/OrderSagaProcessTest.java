package com.project.order_service.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 결제 응답 → PAID. 카드는 NOT_PROCESSED 에서, 무통장입금은 UNPAID(입금 대기)에서 —
 * 둘 다 같은 길로 재고가 나가고 장바구니가 비워진다(docs/49 §11).
 */
@DisplayName("결제 응답 처리")
class OrderSagaProcessTest {

    private OrderRepository orderRepository;
    private com.project.order_service.outbox.PaymentOutboxHelper outbox;
    private OrderSaga saga;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        OrderProductRepository orderProductRepository = mock(OrderProductRepository.class);
        outbox = mock(com.project.order_service.outbox.PaymentOutboxHelper.class);
        saga = new OrderSaga(orderRepository, orderProductRepository, outbox, mock(CouponRollbackKafkaPublisher.class), mock(CartRollbackKafkaPublisher.class),
                mock(com.project.order_service.service.OrderCacheEvictor.class));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L).build())));
    }

    private PaymentResponse response() {
        return PaymentResponse.builder().orderId(1L).build();
    }

    @Test
    @DisplayName("입금 대기(UNPAID) 주문도 결제 응답으로 PAID 가 되고 재고가 나간다")
    void unpaidBecomesPaid() {
        Order order = Order.builder().orderId(1L).customerId(7L).orderStatus(OrderStatus.UNPAID).build();
        when(orderRepository.findByOrderIdAndOrderStatusIn(eq(1L), eq(OrderSaga.AWAITING_PAYMENT)))
                .thenReturn(Optional.of(order));

        saga.process(response());

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        // 🔴 결제 시각도 남는다 — 예전에는 무통장입금 수동 확인에서만 채워졌다.
        assertThat(order.getPaidCreatedAt()).isNotNull();
        // 키는 메시지 키 — CDC 릴레이(EventRouter)가 aggregate_id 를 키로 쓴다: 재고는 eventId, 장바구니는 사용자 id
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT), eq("order-1"), any());
        org.mockito.ArgumentCaptor<Object> cart = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.CART_EMPTY_EVENT), eq("7"), cart.capture());
        // 🔴 주문 id 를 싣는다 — cart 가 같은 주문의 재전달을 거른다(cart-empty-<orderId>)
        assertThat(((com.project.common.message.dto.request.CartEmptyRequest) cart.getValue()).getOrderId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 결제된 주문에 응답이 또 오면 아무것도 하지 않는다")
    void alreadyPaidIsIgnored() {
        when(orderRepository.findByOrderIdAndOrderStatusIn(eq(1L), any())).thenReturn(Optional.empty());

        saga.process(response());

        verify(outbox, never()).saveEventOutbox(any(), any(), any());
        
    }

    @Test
    @DisplayName("결제 응답을 기다리는 상태는 둘뿐이다")
    void awaitingStatuses() {
        assertThat(OrderSaga.AWAITING_PAYMENT).containsExactly(OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID);
    }
}
