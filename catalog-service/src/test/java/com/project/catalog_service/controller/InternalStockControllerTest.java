package com.project.catalog_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.service.StockReservationService;
import com.project.common.dto.stock.StockReservationRequest;
import com.project.common.dto.stock.StockReservationResult;

/**
 * 내부 재고 예약(HTTP) — 잘못된 요청은 400. 예전에는 500 이라 order 가 "닿지 못함" 으로 읽고 예약 없이 주문을 진행했다.
 *
 * <p>[JA] 内部在庫予約(HTTP) — 不正な要求は 400。以前は 500 で、order が予約なしで進めていた。
 */
@DisplayName("InternalStockController — 잘못된 예약 요청은 400")
class InternalStockControllerTest {

    @Test
    @DisplayName("🔴 수량 1 미만 같은 잘못된 요청은 400, 정상은 200")
    void invalidRequestIs400() {
        StockReservationService reservations = mock(StockReservationService.class);
        InternalStockController controller = new InternalStockController(reservations);
        StockReservationRequest bad = StockReservationRequest.builder().orderId(1L).build();
        StockReservationRequest good = StockReservationRequest.builder().orderId(2L).build();
        when(reservations.reserve(bad)).thenThrow(new IllegalArgumentException("예약할 주문과 줄이 필요합니다."));
        when(reservations.reserve(good)).thenReturn(StockReservationResult.builder().held(true).build());

        assertThat(controller.reserve(bad).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.reserve(good).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.reserve(good).getBody().isHeld()).isTrue();
    }
}
