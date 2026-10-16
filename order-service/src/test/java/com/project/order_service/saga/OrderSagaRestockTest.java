package com.project.order_service.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.mockito.ArgumentCaptor;

import com.project.common.constants.OrderStatus;
import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.ports.output.message.publisher.CartRollbackKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.CouponRollbackKafkaPublisher;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 재고 복원 — 결제 때 보낸 차감의 역.
 *
 * <p>여기 고정하는 것은 <b>언제</b> 보내는가(물건이 창고에 있을 때만)와 <b>무엇을</b>
 * 보내는가(같은 항목, restock=true, 키 restock-&lt;id&gt;), 그리고 <b>한 번만</b> 보내는가다.
 */
@DisplayName("재고 복원")
class OrderSagaRestockTest {

    private OrderRepository orderRepository;
    private OrderProductRepository orderProductRepository;
    private com.project.order_service.outbox.PaymentOutboxHelper outbox;
    private CouponRollbackKafkaPublisher couponPublisher;
    private OrderSaga saga;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderProductRepository = mock(OrderProductRepository.class);
        outbox = mock(com.project.order_service.outbox.PaymentOutboxHelper.class);
        couponPublisher = mock(CouponRollbackKafkaPublisher.class);
        saga = new OrderSaga(orderRepository, orderProductRepository, outbox, couponPublisher, mock(CartRollbackKafkaPublisher.class),
                mock(com.project.order_service.service.OrderCacheEvictor.class));

        // 발송 전 전액 환불 뒤의 모습: 전 수량이 환불됐고 "돌아온 것" 으로 세어져 있다.
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L)
                        .refundedQty(2).returnedQty(2).build(),
                OrderProduct.builder().orderProductId(102L).productId(11L).qty(1).size("L").colorId(6L)
                        .refundedQty(1).returnedQty(1).build())));
        when(orderRepository.markStockRestored(anyLong(), any())).thenReturn(1);
        when(orderProductRepository.addRestocked(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
    }

    /** 발송된 뒤 환불된 주문: 환불됐지만 아직 안 돌아왔다 — 보낼 것이 없다. */
    private void linesStillOut() {
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L)
                        .refundedQty(2).returnedQty(0).build())));
    }

    private static Order refunded(boolean awaitingReturn) {
        return Order.builder().orderId(1L).trackingId("track-1").customerId(7L)
                .orderStatus(OrderStatus.REFUNDED).couponApplied("SUMMER")
                .awaitingReturn(awaitingReturn).build();
    }

    @Test
    @DisplayName("발송 전 환불이면 쿠폰과 함께 재고를 바로 돌려준다 — 같은 항목, restock=true, 키 restock-<id>")
    void refundBeforeDispatchRestocksNow() {
        saga.compensateRefund(refunded(false));

        ArgumentCaptor<ProductUpdateRequest> captor = ArgumentCaptor.forClass(ProductUpdateRequest.class);
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT), any(), captor.capture());
        ProductUpdateRequest sent = captor.getValue();
        assertThat(sent.isRestock()).isTrue();
        assertThat(sent.getEventId()).isEqualTo("restock-1-u3");
        assertThat(sent.getOrderProducts()).hasSize(2);
        assertThat(sent.getOrderProducts().get(0).getProductId()).isEqualTo(10L);
        assertThat(sent.getOrderProducts().get(0).getQty()).as("수량은 양수 그대로 — 부호는 catalog 가 뒤집는다").isEqualTo(2);
        assertThat(sent.getOrderProducts().get(0).getSize()).isEqualTo("M");
        assertThat(sent.getOrderProducts().get(0).getColorId()).isEqualTo(5L);

        verify(couponPublisher).publish(any(CouponRollbackRequest.class));
        verify(orderRepository).markStockRestored(eq(1L), any(LocalDateTime.class));
        verify(orderProductRepository).addRestocked(101L, 2);
        verify(orderProductRepository).addRestocked(102L, 1);
    }

    /** 🔴 물건이 밖에 있는데 재고를 늘리면 없는 물건을 판다. */
    @Test
    @DisplayName("🔴 발송된 뒤의 환불은 재고를 돌려주지 않는다 — 반품 입고 때")
    void refundAfterDispatchWaitsForReturn() {
        linesStillOut();

        saga.compensateRefund(refunded(true));

        verify(outbox, never()).saveEventOutbox(any(), any(), any());
        verify(orderRepository, never()).markStockRestored(anyLong(), any());
        verify(couponPublisher).publish(any(CouponRollbackRequest.class));   // 쿠폰은 돌려준다
    }

    /** 부분 반품: 돌아온 만큼만, 이미 되돌린 수와 폐기는 빼고. */
    @Test
    @DisplayName("부분 반품 뒤 restock 은 돌아왔지만 아직 처리되지 않은 수량만 보낸다")
    void restockSendsOnlyPendingUnits() {
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of(
                // 2개 중 1개 돌아옴, 아직 안 되돌림 → 1
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L)
                        .refundedQty(2).returnedQty(1).build(),
                // 3개 돌아옴, 1개는 이미 되돌렸고 1개는 폐기 → 1
                OrderProduct.builder().orderProductId(102L).productId(11L).qty(3).size("L").colorId(6L)
                        .refundedQty(3).returnedQty(3).restockedQty(1).discardedQty(1).build(),
                // 다 돌아왔고 다 되돌림 → 0 (실리지 않는다)
                OrderProduct.builder().orderProductId(103L).productId(12L).qty(1).size("S").colorId(7L)
                        .refundedQty(1).returnedQty(1).restockedQty(1).build())));

        saga.restock(refunded(true));

        ArgumentCaptor<ProductUpdateRequest> captor = ArgumentCaptor.forClass(ProductUpdateRequest.class);
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT), any(), captor.capture());
        ProductUpdateRequest sent = captor.getValue();
        assertThat(sent.getOrderProducts()).hasSize(2);
        assertThat(sent.getOrderProducts().get(0).getProductId()).isEqualTo(10L);
        assertThat(sent.getOrderProducts().get(0).getQty()).isEqualTo(1);
        assertThat(sent.getOrderProducts().get(1).getProductId()).isEqualTo(11L);
        assertThat(sent.getOrderProducts().get(1).getQty()).isEqualTo(1);
        // 누적: 이미 2 되돌렸고 이번에 2 → u4. 같은 상태에서 다시 불리면 같은 키(재시도가 접힌다).
        assertThat(sent.getEventId()).isEqualTo("restock-1-u4");
        verify(orderProductRepository).addRestocked(101L, 1);
        verify(orderProductRepository).addRestocked(102L, 1);
        verify(orderProductRepository, never()).addRestocked(eq(103L), org.mockito.ArgumentMatchers.anyInt());
    }

    /** 🔴 두 번 보내면 재고가 두 번 는다. 품목 카운터가 먼저 막고, catalog 의 eventId 가 두 번째로 막는다. */
    @Test
    @DisplayName("🔴 이미 다 되돌린 주문은 다시 보내지 않는다")
    void alreadyRestockedIsSkipped() {
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of(
                OrderProduct.builder().orderProductId(101L).productId(10L).qty(2).size("M").colorId(5L)
                        .refundedQty(2).returnedQty(2).restockedQty(2).build())));
        Order order = refunded(false);
        order.setStockRestoredAt(LocalDateTime.now().minusDays(1));

        saga.restock(order);
        saga.compensateRefund(order);

        verify(outbox, never()).saveEventOutbox(any(), any(), any());
    }

    /** 일부만 환불된 주문은 여전히 그 쿠폰으로 산 주문이다 — 돌려주면 두 번 쓰게 된다. */
    @Test
    @DisplayName("부분 환불에는 쿠폰을 돌려주지 않는다 — 전액이 다 돌아갔을 때만")
    void couponIsRestoredOnlyWhenFullyRefunded() {
        Order partial = Order.builder().orderId(1L).trackingId("track-1").customerId(7L)
                .orderStatus(OrderStatus.PAID).couponApplied("SUMMER").build();

        saga.compensateRefund(partial);

        verify(couponPublisher, never()).publish(any(CouponRollbackRequest.class));
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.PRODUCT_UPDATE_EVENT), any(), any());   // 재고는 카운터대로 돌려준다
    }

    /** 아웃박스 행을 먼저 쓴다 — 그것이 실패했는데 DB 가 "돌려줬다" 가 되면 안 된다(같은 트랜잭션이라 둘 다 없다). */
    @Test
    @DisplayName("아웃박스 쓰기가 실패하면 표시하지 않는다 — 다음 호출이 다시 보낸다")
    void publishFailureLeavesNoMark() {
        doThrow(new RuntimeException("db down")).when(outbox).saveEventOutbox(any(), any(), any());

        saga.restock(refunded(false));   // 예외는 삼킨다 — 환불은 이미 반영됐다

        verify(orderRepository, never()).markStockRestored(anyLong(), any());
        verify(orderProductRepository, never()).addRestocked(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("품목이 없으면 보낼 것이 없다")
    void noItemsNothingToSend() {
        when(orderProductRepository.findByOrder_OrderId(1L)).thenReturn(Optional.of(List.of()));

        saga.restock(refunded(false));

        verify(outbox, never()).saveEventOutbox(any(), any(), any());
        verify(orderRepository, never()).markStockRestored(anyLong(), any());
    }

    /** 🔴 행과 표시는 한 트랜잭션 — 표시가 일부 실패하면(동시 입고) 아웃박스 행째 롤백한다. 보내면 실제보다 많이 돌려준다. */
    @Test
    @DisplayName("🔴 재고 복원은 한 트랜잭션 — 정상이면 커밋, 표시가 일부 실패하면 롤백(아웃박스 행도 없다)")
    void restockIsOneTransaction() {
        org.springframework.transaction.PlatformTransactionManager tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(tm.getTransaction(any())).thenReturn(status);
        saga.setTransactionManager(tm);

        saga.restock(refunded(false));
        verify(tm).commit(status);

        when(orderProductRepository.addRestocked(eq(102L), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);
        org.mockito.Mockito.clearInvocations(orderRepository);
        saga.restock(refunded(false));   // 예외는 삼킨다
        verify(tm).rollback(status);
        verify(orderRepository, never()).markStockRestored(anyLong(), any());
    }
}
