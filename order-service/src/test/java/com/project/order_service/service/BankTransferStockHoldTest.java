package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.stock.OrderStockHold;

/**
 * 무통장 입금 — 입금 기한까지 재고를 잡고, 모자라면 입금 안내를 내지 않는다 (docs/59).
 */
@DisplayName("BankTransferService — 재고 예약")
class BankTransferStockHoldTest {

    OrderCacheService cache = mock(OrderCacheService.class);
    OrderDataService data = mock(OrderDataService.class);
    com.project.order_service.saga.OrderSaga saga = mock(com.project.order_service.saga.OrderSaga.class);
    OrderStockHold stockHold = mock(OrderStockHold.class);
    BankTransferService service = new BankTransferService(cache, data, saga,
            mock(com.project.order_service.payment.PaymentCurrencyPolicy.class),
            mock(com.project.order_service.payment.PaymentGateways.class));

    {
        service.setStockHold(stockHold);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bank", "은행");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "account", "000-000");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "holder", "상점");
    }

    private Order order(OrderStatus status) {
        Order o = Order.builder().orderId(77L).customerId(7L).trackingId("t-1").orderStatus(status).build();
        o.setOrderedProducts(List.of(OrderProduct.builder().productId(10L).colorId(5L).size("M").qty(1).name("티셔츠").build()));
        return o;
    }

    @Test
    @DisplayName("🔴 결제 화면에 머무는 사이 팔렸다면 입금 안내를 내지 않는다 — 없는 물건에 돈을 보내게 하지 않는다")
    void shortBlocksStart() {
        when(data.getOrderByTrackingId("t-1")).thenReturn(order(OrderStatus.NOT_PROCESSED));
        var shortage = StockReservationResult.Shortage.builder().productId(10L).colorId(5L).size("M").requested(1).available(0).build();
        when(stockHold.hold(any(), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.SHORT, null, List.of(shortage)));

        assertThatThrownBy(() -> service.start("t-1", 7L, "홍길동"))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("입금을 시작하지 못했습니다")
                .hasMessageContaining("티셔츠(M) 품절");
        verify(cache, never()).startBankTransfer(anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("잡히면 입금 기한(+1시간)까지 늘리고 안내를 낸다")
    void heldExtendsToDeadline() {
        Order o = order(OrderStatus.NOT_PROCESSED);
        when(data.getOrderByTrackingId("t-1")).thenReturn(o);
        when(stockHold.hold(any(), any())).thenReturn(new OrderStockHold.Outcome(OrderStockHold.Kind.HELD,
                java.time.LocalDateTime.of(2026, 9, 27, 10, 0), List.of()));
        when(cache.startBankTransfer(anyString(), anyLong(), anyString(), anyInt())).thenReturn(order(OrderStatus.UNPAID));

        service.start("t-1", 7L, "홍길동");

        verify(stockHold).hold(eq(o), eq(Duration.ofHours(49)));
        verify(cache).recordStockHold(eq(77L), any());
        verify(cache).startBankTransfer("t-1", 7L, "홍길동", 48);
    }

    @Test
    @DisplayName("🔒 남의 주문이면 예약을 건드리지 않는다 — 소유 확인은 뒤의 데이터 계층이 한다")
    void othersOrderNotTouched() {
        when(data.getOrderByTrackingId("t-1")).thenReturn(order(OrderStatus.NOT_PROCESSED));
        when(cache.startBankTransfer(anyString(), anyLong(), anyString(), anyInt())).thenThrow(new PaymentVerificationException("주문을 찾을 수 없습니다"));

        assertThatThrownBy(() -> service.start("t-1", 999L, "홍길동")).isInstanceOf(PaymentVerificationException.class);
        verify(stockHold, never()).hold(any(), any());
    }

    @Test
    @DisplayName("입금 전 취소·기한 만료는 예약을 푼다")
    void cancelAndExpireRelease() {
        Order canceled = order(OrderStatus.CANCELED);
        when(data.getOrderByTrackingId("t-1")).thenReturn(order(OrderStatus.UNPAID));
        when(cache.cancelUnpaid("t-1", 7L, false)).thenReturn(canceled);
        service.cancel("t-1", 7L, false);
        verify(stockHold).release(77L);

        when(cache.expireUnpaid(any())).thenReturn(List.of(order(OrderStatus.CANCELED)));
        service.expire();
        verify(stockHold, org.mockito.Mockito.times(2)).release(77L);
    }
}
