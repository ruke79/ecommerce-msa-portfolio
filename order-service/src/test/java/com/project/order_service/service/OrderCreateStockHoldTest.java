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
    CartProductDto line;

    @BeforeEach
    void setUp() {
        CartDto cart = org.mockito.Mockito.mock(CartDto.class);
        line = org.mockito.Mockito.mock(CartProductDto.class);
        when(line.getQty()).thenReturn(3);
        when(cart.getProducts()).thenReturn(List.of(line));
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
        // 기한 초과면 catalog 가 뒤늦게 잡았을 수 있다 — 새 주문이라 한 번 풀어 둔다(유령 예약 방지)
        verify(stockHold).release(77L);
    }

    @Test
    @DisplayName("🔴 catalog 가 요청을 거절하면 주문을 버린다 — 예전에는 '닿지 못함' 처럼 예약 없이 결제로 갔다")
    void rejectedDiscards() {
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.REJECTED, null, List.of()));

        assertThatThrownBy(() -> service.createOrder(new OrderRequest(), 7L))
                .isInstanceOf(OrderDomainException.class).hasMessage(OrderService.UNORDERABLE_MESSAGE);
        verify(cache).discardUnplaced(77L);
    }

    @Test
    @DisplayName("🔴 장바구니 줄의 수량이 1 ~ 1000 밖이면 금액을 정하기 전에 거절한다 — 음수 수량이 주문 금액을 깎았다")
    void outOfRangeQuantityIsRejectedBeforePricing() {
        for (int qty : new int[] { 0, -5, OrderService.MAX_LINE_QTY + 1 }) {
            when(line.getQty()).thenReturn(qty);
            assertThatThrownBy(() -> service.createOrder(new OrderRequest(), 7L))
                    .isInstanceOf(OrderDomainException.class).hasMessage(OrderService.UNORDERABLE_MESSAGE);
        }
        verify(orderPricing, never()).priceOrder(any(), any(), any());
        verify(cache, never()).createOrder(any(), any(), any(), anyLong());
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

    // ---------------------------------------------------------------- 멱등 키 (V26)

    @Test
    @DisplayName("🔴 같은 멱등 키로 다시 오면 새 주문을 만들지 않고 이미 만든 주문을 돌려준다 — 장바구니 · 재고도 건드리지 않는다")
    void sameCreateKeyReturnsTheExistingOrder() {
        when(cache.findByCreateKey(7L, "checkout-key-1")).thenReturn(created);

        assertThat(service.createOrder(new OrderRequest(), 7L, "checkout-key-1")).isSameAs(created);

        verify(cartServiceClient, never()).getCart(anyLong());
        verify(cache, never()).createOrder(any(), any(), any(), anyLong(), any());
        verify(stockHold, never()).hold(any(), any());
    }

    @Test
    @DisplayName("🔴 같은 키가 동시에 와서 늦은 쪽이 유니크 제약에 걸리면 앞선 주문을 돌려준다 — 재고를 다시 잡지 않는다")
    void concurrentSameKeyReturnsTheWinner() {
        Order winner = Order.builder().orderId(88L).build();
        when(cache.findByCreateKey(7L, "checkout-key-2")).thenReturn(null, winner);
        when(cache.createOrder(any(), any(), any(), anyLong(), eq("checkout-key-2")))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_orders_customer_create_key"));

        assertThat(service.createOrder(new OrderRequest(), 7L, "checkout-key-2")).isSameAs(winner);
        verify(stockHold, never()).hold(any(), any());
    }

    @Test
    @DisplayName("새 키면 그 키를 실어 만든다 · 쓸 수 없는 모양의 키는 없는 것으로 본다")
    void newKeyIsStoredAndBadKeysIgnored() {
        when(cache.createOrder(any(), any(), any(), anyLong(), eq("checkout-key-3"))).thenReturn(created);
        when(stockHold.hold(eq(created), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.HELD, null, List.of()));

        assertThat(service.createOrder(new OrderRequest(), 7L, "checkout-key-3")).isSameAs(created);

        assertThat(OrderService.usableCreateKey(" 0f8fad5b-d9cb-469f-a165-70867728950e ")).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(OrderService.usableCreateKey("short")).isNull();
        assertThat(OrderService.usableCreateKey("x".repeat(65))).isNull();
        assertThat(OrderService.usableCreateKey("bad key with spaces")).isNull();
        assertThat(OrderService.usableCreateKey(null)).isNull();
    }
}
