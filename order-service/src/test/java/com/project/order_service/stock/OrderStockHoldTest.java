package com.project.order_service.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결제 전 재고 예약 — order 쪽 (docs/59).
 */
@DisplayName("OrderStockHold — 결제 전 재고 예약")
class OrderStockHoldTest {

    CatalogServiceClient catalog = mock(CatalogServiceClient.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    OrderStockHold hold = new OrderStockHold(new FeignStockReservationPort(catalog), meters, true, 30);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Order order() {
        Order o = Order.builder().orderId(77L).build();
        o.setOrderedProducts(List.of(
                OrderProduct.builder().productId(10L).colorId(5L).size("M").qty(2).name("티셔츠").build(),
                OrderProduct.builder().productId(11L).colorId(6L).size("L").qty(1).name("바지").build()));
        return o;
    }

    private double count(String result) {
        var c = meters.find("order_stock_hold_total").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("주문의 모든 줄을 주문 id 로 잡는다 — 결제 확정 차감의 order-<id> 와 같은 키")
    void sendsAllLines() {
        when(catalog.reserveStock(any())).thenReturn(StockReservationResult.builder().held(true)
                .expiresAt(Instant.parse("2026-09-25T01:30:00Z")).build());

        OrderStockHold.Outcome out = hold.hold(order(), Duration.ofMinutes(30));

        ArgumentCaptor<StockReservationRequest> req = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(catalog).reserveStock(req.capture());
        assertThat(req.getValue().getOrderId()).isEqualTo(77L);
        assertThat(req.getValue().getTtlSeconds()).isEqualTo(1800);
        assertThat(req.getValue().getLines()).extracting(StockReservationRequest.Line::getProductId).containsExactly(10L, 11L);
        assertThat(out.isHeld()).isTrue();
        assertThat(out.holdUntil()).isNotNull();
        assertThat(count("held")).isEqualTo(1);
    }

    @Test
    @DisplayName("모자라면 SHORT 와 모자란 줄")
    void short_() {
        var shortage = StockReservationResult.Shortage.builder().productId(10L).colorId(5L).size("M").requested(2).available(1).build();
        when(catalog.reserveStock(any())).thenReturn(StockReservationResult.builder().held(false).shortages(List.of(shortage)).build());

        OrderStockHold.Outcome out = hold.hold(order(), Duration.ofMinutes(30));

        assertThat(out.isShort()).isTrue();
        assertThat(out.shortages()).containsExactly(shortage);
        assertThat(count("short")).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 catalog 에 닿지 못하면 막지 않는다(fail-open) — 세고, 예약 없이 간다")
    void failOpen() {
        when(catalog.reserveStock(any())).thenThrow(new RuntimeException("connect timed out"));

        OrderStockHold.Outcome out = hold.hold(order(), Duration.ofMinutes(30));

        assertThat(out.kind()).isEqualTo(OrderStockHold.Kind.UNAVAILABLE);
        assertThat(out.isShort()).isFalse();
        assertThat(count("unavailable")).isEqualTo(1);
    }

    @Test
    @DisplayName("꺼져 있으면 catalog 를 부르지 않는다")
    void disabled() {
        OrderStockHold off = new OrderStockHold(new FeignStockReservationPort(catalog), meters, false, 30);

        assertThat(off.hold(order(), Duration.ofMinutes(30)).kind()).isEqualTo(OrderStockHold.Kind.DISABLED);
        off.release(77L);
        verify(catalog, never()).reserveStock(any());
        verify(catalog, never()).releaseStock(any());
    }

    @Test
    @DisplayName("풀기 실패는 던지지 않는다 — catalog 의 만료가 푼다")
    void releaseSwallows() {
        doThrow(new RuntimeException("down")).when(catalog).releaseStock(77L);

        hold.release(77L);   // 예외 없음
        verify(catalog).releaseStock(77L);
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서는 커밋된 뒤에 푼다 — 롤백된 취소로 재고를 풀지 않는다")
    void releaseAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        hold.releaseAfterCommit(77L);
        verify(catalog, never()).releaseStock(any());

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(catalog).releaseStock(77L);
    }

    @Test
    @DisplayName("🔴 catalog 가 요청을 거절하면(HTTP 400 · gRPC INVALID_ARGUMENT) REJECTED — '닿지 못함' 으로 읽어 예약 없이 가지 않는다")
    void rejectionIsNotFailOpen() {
        feign.Request req = feign.Request.create(feign.Request.HttpMethod.POST, "/api/internal/stock/reservations",
                java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        when(catalog.reserveStock(any())).thenThrow(new feign.FeignException.BadRequest("bad", req, null, java.util.Map.of()));

        OrderStockHold.Outcome out = hold.hold(order(), Duration.ofMinutes(30));

        assertThat(out.isRejected()).isTrue();
        assertThat(count("rejected")).isEqualTo(1);
        assertThat(count("unavailable")).isZero();
        assertThat(OrderStockHold.isRejection(io.grpc.Status.INVALID_ARGUMENT.asRuntimeException())).isTrue();
        assertThat(OrderStockHold.isRejection(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException())).isFalse();
        assertThat(OrderStockHold.isRejection(io.grpc.Status.UNAVAILABLE.asRuntimeException())).isFalse();
    }
}
