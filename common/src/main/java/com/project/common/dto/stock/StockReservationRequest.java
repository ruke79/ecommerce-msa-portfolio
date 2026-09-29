package com.project.common.dto.stock;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 전 재고 예약 요청 — order-service → catalog-service (docs/59).
 *
 * <p>주문 하나의 모든 줄을 <b>한꺼번에</b> 잡는다(전부 아니면 전무). 같은 주문으로 다시 부르면 기존 예약을 이어 붙인다
 * (만료만 늘린다) — 재시도·무통장 전환이 같은 호출이다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReservationRequest {

    private Long orderId;
    /** 예약을 유지할 시간(초). catalog 가 상한을 둔다. */
    private long ttlSeconds;
    private List<Line> lines;

    /** 재고 한 단위 — 상품·색상·사이즈. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private Long colorId;
        private String size;
        private int qty;
    }
}
