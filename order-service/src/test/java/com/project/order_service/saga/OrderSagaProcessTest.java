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
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 결제 응답 → PAID. 카드는 NOT_PROCESSED 에서, 무통장입금은 UNPAID(입금 대기)에서 —
 * 둘 다 같은 길로 재고가 나가고 장바구니가 비워진다(docs/49 §11).
 */
@DisplayName("결제 응답 처리")
class OrderSagaProcessTest {

    private OrderRepository orderRepository;
    private ProductUpdateKafkaPublisher productPublisher;
    private CartEmptyKafkaPublisher cartPublisher;
    private OrderSaga saga;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        OrderProductRepository orderProductRepository = mock(OrderProductRepository.class);
        productPublisher = mock(ProductUpdateKafkaPublisher.class);
        cartPublisher = mock(CartEmptyKafkaPublisher.class);
        saga = new OrderSaga(orderRepository, orderProductRepository, cartPublisher,
                productPublisher, mock(CouponRollbackKafkaPublisher.class), mock(CartRollbackKafkaPublisher.class),
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
        verify(productPublisher).publish(any());
        verify(cartPublisher).publish(any());
    }

    @Test
    @DisplayName("이미 결제된 주문에 응답이 또 오면 아무것도 하지 않는다")
    void alreadyPaidIsIgnored() {
        when(orderRepository.findByOrderIdAndOrderStatusIn(eq(1L), any())).thenReturn(Optional.empty());

        saga.process(response());

        verify(productPublisher, never()).publish(any());
        verify(cartPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("결제 응답을 기다리는 상태는 둘뿐이다")
    void awaitingStatuses() {
        assertThat(OrderSaga.AWAITING_PAYMENT).containsExactly(OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID);
    }
}
