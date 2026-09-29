package com.project.order_service.saga;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.order_service.model.Order;
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;
import com.project.order_service.stock.OrderStockHold;

/**
 * 결제가 실패해 주문을 접으면 잡아 둔 재고를 푼다 (docs/59).
 */
@DisplayName("OrderSaga.rollback — 결제 실패 시 재고 예약 해제")
class OrderSagaStockReleaseTest {

    OrderRepository orders = mock(OrderRepository.class);
    OrderStockHold stockHold = mock(OrderStockHold.class);
    OrderSaga saga = new OrderSaga(orders, mock(OrderProductRepository.class), mock(CartEmptyKafkaPublisher.class),
            mock(ProductUpdateKafkaPublisher.class), mock(CouponRollbackKafkaPublisher.class), mock(CartRollbackKafkaPublisher.class),
            mock(com.project.order_service.service.OrderCacheEvictor.class));

    {
        saga.setStockHold(stockHold);
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("결제 전 주문이 결제 실패로 접히면 예약을 푼다(커밋 뒤)")
    void releasesOnRollback() {
        Order order = Order.builder().orderId(1L).customerId(7L).orderStatus(OrderStatus.NOT_PROCESSED).build();
        when(orders.findByOrderIdAndOrderStatus(1L, OrderStatus.NOT_PROCESSED)).thenReturn(Optional.of(order));

        saga.rollback(PaymentResponse.builder().orderId(1L).build());

        verify(stockHold).releaseAfterCommit(1L);
    }

    @Test
    @DisplayName("접을 주문이 없으면(이미 결제됨 등) 풀지 않는다")
    void noReleaseWhenNothingRolledBack() {
        when(orders.findByOrderIdAndOrderStatus(any(), any())).thenReturn(Optional.empty());

        saga.rollback(PaymentResponse.builder().orderId(1L).build());

        verify(stockHold, never()).releaseAfterCommit(any());
    }
}
