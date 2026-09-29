package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.project.common.constants.OrderStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.domain.exception.OrderNotFoundException;
import com.project.order_service.model.Order;
import com.project.order_service.ports.output.message.publisher.OrderCompletedKafkaPublisher;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 구매 확정 — {@code DELIVERED → COMPLETED}.
 *
 * <p>🐛 {@code OrderStatus.COMPLETED} 는 선언만 있고 <b>그 값을 넣는 코드가 저장소 어디에도
 * 없었다.</b> 배달 완료가 끝이었다. 여기서 채운다 — 손님이 누르거나, 기한이 지나 자동으로.
 */
@DisplayName("order-service — 구매 확정")
class OrderPurchaseConfirmationServiceTest {

    private static final long OWNER_ID = 100L;
    // 요청자는 게이트웨이가 검증한 토큰의 uid(User-Id) 다 — 이메일로 고객 사본을 찾지 않는다(docs/48 §6-①).
    private static final Long OWNER = OWNER_ID;
    private static final Long INTRUDER = 200L;

    private OrderRepository orderRepository;
    private OrderCacheEvictor evictor;
    private OrderCompletedKafkaPublisher publisher;
    private OrderPurchaseConfirmationService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        evictor = mock(OrderCacheEvictor.class);
        publisher = mock(OrderCompletedKafkaPublisher.class);
        service = new OrderPurchaseConfirmationService(orderRepository, evictor, publisher,
                true, 7, 200);

        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order order(OrderStatus status) {
        Order order = Order.builder().orderId(1L).customerId(OWNER_ID).orderStatus(status)
                .deliveredCreatedAt(status == OrderStatus.DELIVERED ? LocalDateTime.now(ZoneOffset.UTC) : null)
                .build();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("🔴 배달 완료된 자기 주문을 확정하면 COMPLETED 가 되고 시각이 찍히며 알림이 나간다")
    void deliveredOrderBecomesCompleted() {
        Order order = order(OrderStatus.DELIVERED);

        Order result = service.confirmByCustomer(1L, OWNER);

        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.isAutoCompleted()).isFalse();
        verify(orderRepository).save(order);
        verify(evictor).evictOrderCaches(eq(1L), anyString());
        verify(publisher).publish(order);
    }

    /** 두 번 누르거나 자동 확정과 겹쳐도 두 번째는 아무 일도 하지 않는다. */
    @Test
    @DisplayName("이미 확정된 주문은 그대로 돌려준다 — 저장도 알림도 없다")
    void alreadyCompletedIsIdempotent() {
        Order order = order(OrderStatus.COMPLETED);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        order.setCompletedAt(before);

        Order result = service.confirmByCustomer(1L, OWNER);

        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isEqualTo(before);
        verify(orderRepository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("🔴 배달 완료가 아니면 거절한다 — 오는 중·결제만 됨·환불·취소")
    void rejectsAnythingButDelivered() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.DISPATCHED,
                OrderStatus.REFUNDED, OrderStatus.CANCELED, OrderStatus.NOT_PROCESSED, OrderStatus.UNPAID}) {
            Order order = order(s);
            assertThatThrownBy(() -> service.confirmByCustomer(1L, OWNER)).as(s.name())
                    .isInstanceOf(OrderDomainException.class)
                    .isNotInstanceOf(OrderNotFoundException.class);
            assertThat(order.getOrderStatus()).as(s.name()).isEqualTo(s);
        }
        verify(orderRepository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    /** 🔒 남의 주문은 403 이 아니라 404 — 번호를 훑어 존재 여부를 알아내지 못하게. */
    @Test
    @DisplayName("🔒 남의 주문이나 없는 주문은 '찾을 수 없음' 이다 — 존재를 알려 주지 않는다")
    void strangersAndUnknownOrdersLookTheSame() {
        Order order = order(OrderStatus.DELIVERED);
        assertThatThrownBy(() -> service.confirmByCustomer(1L, INTRUDER)).isInstanceOf(OrderNotFoundException.class);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.DELIVERED);

        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirmByCustomer(9L, OWNER)).isInstanceOf(OrderNotFoundException.class);

        assertThatThrownBy(() -> service.confirmByCustomer(1L, null))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 배달 완료 뒤 기한이 지난 주문은 자동으로 확정된다 — autoCompleted 가 참이고 알림이 나간다")
    void autoConfirmsOverdueDeliveries() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 20, 12, 0);
        Order a = Order.builder().orderId(1L).customerId(OWNER_ID).orderStatus(OrderStatus.DELIVERED)
                .deliveredCreatedAt(now.minusDays(8)).build();
        Order b = Order.builder().orderId(2L).customerId(OWNER_ID).orderStatus(OrderStatus.DELIVERED)
                .deliveredCreatedAt(now.minusDays(30)).build();
        when(orderRepository.findByOrderStatusAndDeliveredCreatedAtBefore(eq(OrderStatus.DELIVERED), any(), any()))
                .thenReturn(List.of(a, b));

        int done = service.autoConfirmDue(now);

        assertThat(done).isEqualTo(2);
        for (Order o : List.of(a, b)) {
            assertThat(o.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(o.isAutoCompleted()).isTrue();
            assertThat(o.getCompletedAt()).isEqualTo(now);
            verify(publisher).publish(o);
            verify(evictor).evictOrderCaches(eq(o.getOrderId()), anyString());
        }

        // 기준선은 now − days 다. 하루라도 덜 지난 주문은 다음 주기에 다시 본다.
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findByOrderStatusAndDeliveredCreatedAtBefore(eq(OrderStatus.DELIVERED),
                cutoff.capture(), any(Pageable.class));
        assertThat(cutoff.getValue()).isEqualTo(now.minusDays(7));
    }

    @Test
    @DisplayName("자동 확정을 끄면 아무것도 훑지 않는다")
    void disabledSweepDoesNothing() {
        service = new OrderPurchaseConfirmationService(orderRepository, evictor, publisher,
                false, 7, 200);

        assertThat(service.autoConfirmDue(LocalDateTime.now(ZoneOffset.UTC))).isZero();

        verify(orderRepository, never()).findByOrderStatusAndDeliveredCreatedAtBefore(any(), any(), any());
    }

    /** 시뮬레이터로 시험할 때 — 0일이면 배달 완료 직후 다음 주기에 확정된다. */
    @Test
    @DisplayName("days=0 이면 기준선이 지금이다 — 시험용")
    void zeroDaysMeansImmediately() {
        service = new OrderPurchaseConfirmationService(orderRepository, evictor, publisher,
                true, 0, 200);
        LocalDateTime now = LocalDateTime.of(2026, 9, 20, 12, 0);
        when(orderRepository.findByOrderStatusAndDeliveredCreatedAtBefore(any(), any(), any())).thenReturn(List.of());

        service.autoConfirmDue(now);

        verify(orderRepository).findByOrderStatusAndDeliveredCreatedAtBefore(eq(OrderStatus.DELIVERED), eq(now),
                any(Pageable.class));
    }
}
