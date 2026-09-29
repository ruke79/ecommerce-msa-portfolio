package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.stock.OrderStockHold;

/**
 * 결제창을 열기 직전 재고 예약을 새로 잡는다(docs/59 §8) — 포트원 가상계좌는 계좌가 PG 창 안에서 발급돼, 여기서 막지 않으면
 * 없는 물건에 계좌가 나간다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderService.renewCheckoutHold — 결제창 직전 예약 갱신")
class CheckoutHoldRenewTest {

    @Mock OrderCacheService cache;
    @Mock OrderStockHold stockHold;
    @InjectMocks OrderService service;

    Order order;

    @BeforeEach
    void setUp() {
        order = Order.builder().orderId(77L).customerId(7L).trackingId("t-1").orderStatus(OrderStatus.NOT_PROCESSED).build();
        order.setOrderedProducts(List.of(OrderProduct.builder().productId(10L).colorId(5L).size("M").qty(2).name("티셔츠").build()));
        when(cache.getOrderByTrackingId("t-1")).thenReturn(order);
        when(stockHold.checkoutTtl()).thenReturn(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("잡았으면 새 만료 시각을 주문에 적고 돌려준다")
    void renewed() {
        LocalDateTime until = LocalDateTime.of(2026, 9, 26, 12, 0);
        when(stockHold.hold(eq(order), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.HELD, until, List.of()));

        var body = service.renewCheckoutHold("t-1", 7L);

        assertThat(body).containsEntry("renewed", true).containsEntry("stockHoldUntil", until.toString());
        verify(cache).recordStockHold(77L, until);
    }

    @Test
    @DisplayName("🔴 모자라면 결제창을 열지 않게 이유를 던진다 — '결제를 시작하지 못했습니다'")
    void shortRefuses() {
        var shortage = StockReservationResult.Shortage.builder().productId(10L).colorId(5L).size("M").requested(2).available(0).build();
        when(stockHold.hold(eq(order), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.SHORT, null, List.of(shortage)));

        assertThatThrownBy(() -> service.renewCheckoutHold("t-1", 7L))
                .isInstanceOf(OrderDomainException.class)
                .hasMessage("재고가 부족해 결제를 시작하지 못했습니다: 티셔츠(M) 품절. 장바구니에서 수량을 조정해 주세요.");
        verify(cache, never()).recordStockHold(anyLong(), any());
    }

    @Test
    @DisplayName("🔒 남의 주문이면 null(404) — 예약을 건드리지 않는다")
    void notOwner() {
        assertThat(service.renewCheckoutHold("t-1", 8L)).isNull();
        verify(stockHold, never()).hold(any(), any());
    }

    @Test
    @DisplayName("결제 전이 아니면 아무것도 하지 않는다")
    void notPending() {
        order.setOrderStatus(OrderStatus.PAID);
        assertThat(service.renewCheckoutHold("t-1", 7L)).containsEntry("renewed", false);
        verify(stockHold, never()).hold(any(), any());
    }

    @Test
    @DisplayName("catalog 에 닿지 못하면 막지 않는다(fail-open)")
    void unavailableProceeds() {
        when(stockHold.hold(eq(order), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.UNAVAILABLE, null, List.of()));
        assertThat(service.renewCheckoutHold("t-1", 7L)).containsEntry("renewed", false);
    }
}
