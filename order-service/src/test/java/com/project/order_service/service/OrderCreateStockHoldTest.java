package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.client.CartServiceClient;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.common.dto.order.request.OrderRequest;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.stock.OrderStockHold;

/**
 * 주문 생성 때 재고를 잡는다 (docs/59).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderService.createOrder — 결제 전 재고 예약")
class OrderCreateStockHoldTest {

    @Mock OrderCacheService cache;
    @Mock CartServiceClient cartServiceClient;
    @Mock OrderPricing orderPricing;
    @Mock OrderStockHold stockHold;
    @Mock ObjectMapper objectMapper;
    @InjectMocks OrderService service;

    Order created;

    @BeforeEach
    void setUp() {
        CartDto cart = org.mockito.Mockito.mock(CartDto.class);
        when(cart.getProducts()).thenReturn(List.of(org.mockito.Mockito.mock(CartProductDto.class)));
        when(cartServiceClient.getCart(anyLong())).thenReturn((ResponseEntity) ResponseEntity.ok(cart));
        when(objectMapper.convertValue(any(), eq(CartDto.class))).thenReturn(cart);
        when(orderPricing.priceOrder(any(), any(), any())).thenReturn(
                new OrderPricing.Amounts(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, null));
        created = Order.builder().orderId(77L).build();
        created.setOrderedProducts(List.of(OrderProduct.builder().productId(10L).colorId(5L).size("M").qty(3).name("티셔츠").build()));
        when(cache.createOrder(any(), any(), any(), anyLong())).thenReturn(created);
        when(stockHold.checkoutTtl()).thenReturn(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("🔴 모자라면 방금 만든 주문을 버리고, 무엇이 몇 개 남았는지 말한다")
    void shortDiscardsAndExplains() {
        var shortage = StockReservationResult.Shortage.builder().productId(10L).colorId(5L).size("M").requested(3).available(1).build();
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.SHORT, null, List.of(shortage)));

        assertThatThrownBy(() -> service.createOrder(new OrderRequest(), 7L))
                .isInstanceOf(OrderDomainException.class)
                .hasMessageContaining("티셔츠(M) 남은 수량 1개");
        verify(cache).discardUnplaced(77L);
        verify(cache, never()).recordStockHold(anyLong(), any());
    }

    @Test
    @DisplayName("품절이면 '품절' 이라고 말한다")
    void soldOutMessage() {
        var shortage = StockReservationResult.Shortage.builder().productId(10L).colorId(5L).size("M").requested(3).available(0).build();
        assertThat(OrderService.shortageMessage(created, List.of(shortage))).contains("티셔츠(M) 품절");
    }

    @Test
    @DisplayName("잡았으면 만료 시각을 주문에 적는다")
    void heldRecordsDeadline() {
        LocalDateTime until = LocalDateTime.of(2026, 9, 25, 10, 30);
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.HELD, until, List.of()));

        Order result = service.createOrder(new OrderRequest(), 7L);

        verify(cache).recordStockHold(77L, until);
        assertThat(result.getStockHoldUntil()).isEqualTo(until);
        verify(cache, never()).discardUnplaced(anyLong());
    }

    @Test
    @DisplayName("🔴 catalog 에 닿지 못하면 주문은 그대로 만든다(fail-open) — 결제 뒤 부족분 환불이 받는다")
    void unavailableProceeds() {
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.UNAVAILABLE, null, List.of()));

        assertThat(service.createOrder(new OrderRequest(), 7L)).isSameAs(created);
        verify(cache, never()).discardUnplaced(anyLong());
    }

    @Test
    @DisplayName("🔴 같은 고객의 앞선 결제 전 주문이 잡은 재고를 먼저 푼다 — 다시 주문한 손님이 자기 예약에 막히지 않게")
    void releasesEarlierHoldsBeforeHolding() {
        when(cache.supersedeStockHolds(eq(7L), eq(77L), any())).thenReturn(List.of(70L, 71L));
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.HELD, null, List.of()));

        service.createOrder(new OrderRequest(), 7L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(cache, stockHold);
        order.verify(cache).supersedeStockHolds(eq(7L), eq(77L), any());
        order.verify(stockHold).release(70L);
        order.verify(stockHold).release(71L);
        order.verify(stockHold).hold(eq(created), any());
    }
}
