package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 배송 상태 → 주문 상태. PROCESSING·DISPATCHED·DELIVERED 가 <b>드디어 채워진다</b>(docs/25 §0).
 *
 * <p>지키는 규칙은 둘이다: 앞으로만 간다, 결제된 주문만 움직인다.
 */
@DisplayName("order-service — 배송 상태 반영")
class OrderDeliveryStatusServiceTest {

    private OrderRepository orderRepository;
    private OrderCacheEvictor evictor;
    private OrderDeliveryStatusService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        evictor = mock(OrderCacheEvictor.class);
        service = new OrderDeliveryStatusService(orderRepository, evictor);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order order(OrderStatus status) {
        Order order = Order.builder().orderId(1L).customerId(7L).orderStatus(status).build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("배송 건이 생기면(PENDING) PAID → PROCESSING, 캐시를 비운다")
    void pendingMakesProcessing() {
        Order order = order(OrderStatus.PAID);

        assertThat(service.apply(1L, "PENDING", "ORDERED")).isTrue();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PROCESSING);
        verify(evictor).evictOrderCaches(eq(1L), anyString());
    }

    @Test
    @DisplayName("송장 등록 이후의 모든 이동 상태는 DISPATCHED 다")
    void inTransitStatesMakeDispatched() {
        for (String s : new String[] {"INFO_RECEIVED", "PICKED_UP", "IN_TRANSIT", "CUSTOMS_HOLD",
                "CUSTOMS_CLEARED", "OUT_FOR_DELIVERY", "ATTEMPT_FAILED", "EXCEPTION"}) {
            Order order = order(OrderStatus.PROCESSING);
            assertThat(service.apply(1L, s, null)).as(s).isTrue();
            assertThat(order.getOrderStatus()).as(s).isEqualTo(OrderStatus.DISPATCHED);
        }
    }

    @Test
    @DisplayName("배달 완료면 DELIVERED 가 되고 deliveredCreatedAt 이 채워진다 — PAID 에서 바로 와도")
    void deliveredFillsTimestamp() {
        Order order = order(OrderStatus.PAID);

        assertThat(service.apply(1L, "DELIVERED", "DELIVERED")).isTrue();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredCreatedAt()).isNotNull();
    }

    /** 🔴 재전달·순서 뒤집힘 — 배달 완료 뒤에 온 "이동 중" 이 주문을 되돌리면 안 된다. */
    @Test
    @DisplayName("🔴 뒤로 가는 이벤트는 무시한다 — DELIVERED 뒤의 IN_TRANSIT, 같은 자리의 재전달")
    void neverGoesBackwards() {
        Order delivered = order(OrderStatus.DELIVERED);
        assertThat(service.apply(1L, "IN_TRANSIT", "IN_TRANSIT")).isFalse();
        assertThat(delivered.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);

        Order dispatched = order(OrderStatus.DISPATCHED);
        assertThat(service.apply(1L, "PICKED_UP", "SHIPPED")).isFalse();
        assertThat(service.apply(1L, "PENDING", "ORDERED")).isFalse();
        assertThat(dispatched.getOrderStatus()).isEqualTo(OrderStatus.DISPATCHED);

        verify(orderRepository, never()).save(any());
        verify(evictor, never()).evictOrderCaches(any(), anyString());
    }

    /** 🔴 발송 뒤 환불된 물건이 그래도 배달됐다 — 주문은 돈의 상태(REFUNDED)를 지킨다. */
    @Test
    @DisplayName("🔴 환불·취소·미결제·구매확정 주문은 배송 이벤트가 와도 그대로다")
    void moneyStateWins() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.REFUNDED, OrderStatus.CANCELED,
                OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID, OrderStatus.COMPLETED}) {
            Order order = order(s);
            assertThat(service.apply(1L, "DELIVERED", "DELIVERED")).as(s.name()).isFalse();
            assertThat(order.getOrderStatus()).as(s.name()).isEqualTo(s);
        }
        verify(orderRepository, never()).save(any());
    }

    /**
     * 🐛 배송을 취소했는데 주문은 DISPATCHED 그대로였고 "배송이 시작된 주문은 환불할 수
     * 없습니다" 에 갇혔다. 손님이 배송을 취소한 이유가 환불인데 그 길이 막혔다.
     */
    @Test
    @DisplayName("🔴 발송 전 배송 취소면 주문은 PAID 로 돌아간다 — 환불이 다시 열린다")
    void cancelledDeliveryRevertsToPaid() {
        Order dispatched = order(OrderStatus.DISPATCHED);
        assertThat(service.apply(1L, "CANCELLED", "CANCELLED")).isTrue();
        assertThat(dispatched.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        verify(evictor).evictOrderCaches(eq(1L), anyString());

        Order processing = order(OrderStatus.PROCESSING);
        assertThat(service.apply(1L, "CANCELLED", "CANCELLED")).isTrue();
        assertThat(processing.getOrderStatus()).isEqualTo(OrderStatus.PAID);
    }

    /** 결제 취소·환불로 시스템이 접은 배송이면 주문은 이미 돈의 상태다 — 되돌리지 않는다. */
    @Test
    @DisplayName("배송 취소여도 배달 완료·환불·취소된 주문은 그대로다")
    void cancelledDeliveryLeavesTerminalOrdersAlone() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.DELIVERED, OrderStatus.REFUNDED,
                OrderStatus.CANCELED, OrderStatus.PAID, OrderStatus.COMPLETED}) {
            Order order = order(s);
            assertThat(service.apply(1L, "CANCELLED", "CANCELLED")).as(s.name()).isFalse();
            assertThat(order.getOrderStatus()).as(s.name()).isEqualTo(s);
        }
    }

    @Test
    @DisplayName("반송은 주문을 움직이지 않는다 — 반품 입고가 처리한다")
    void returnedIsIgnored() {
        Order order = order(OrderStatus.DISPATCHED);
        assertThat(service.apply(1L, "RETURNED", "EXCEPTION")).isFalse();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DISPATCHED);
    }

    @Test
    @DisplayName("모르는 상태는 stage 로 판정한다 — 배송 서비스가 상태를 더해도 깨지지 않는다")
    void unknownStatusFallsBackToStage() {
        Order order = order(OrderStatus.PAID);
        assertThat(service.apply(1L, "HANDED_TO_LOCKER", "IN_TRANSIT")).isTrue();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DISPATCHED);

        assertThat(OrderDeliveryStatusService.targetOf("SOMETHING_NEW", null)).isEmpty();
    }

    @Test
    @DisplayName("모르는 주문이면 false — 재시도해도 같으니 DLT 로 보내지 않는다")
    void unknownOrderIsFalse() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.apply(9L, "DELIVERED", "DELIVERED")).isFalse();
    }
}
