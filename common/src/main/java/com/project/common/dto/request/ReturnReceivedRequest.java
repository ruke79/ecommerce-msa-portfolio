package com.project.common.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 반품 입고 처리 요청 본문 (관리자).
 *
 * <p>{@link RefundRequest} 와 같은 원칙이다 — <b>어느 주문</b>과 <b>재고로 되돌릴 것인가</b>
 * 뿐이다. 수량·상품은 본문에 없다. 되돌릴 수량은 DB 의 주문 품목 그대로이고, 본문에 두면
 * 그 값을 믿게 된다.
 */
@Getter
@Setter
@NoArgsConstructor
public class ReturnReceivedRequest {

    private String trackingId;

    /**
     * 재고로 되돌릴 것인가. 기본 {@code true}. 파손·오염으로 다시 팔 수 없으면 {@code false} —
     * 입고는 기록되지만 재고는 그대로다.
     */
    private Boolean restock;

    /** 부분 반품 — 품목과 수량. 비어 있으면 아직 돌아오지 않은 전부다. */
    private java.util.List<OrderItemQtyRequest> items;

    public boolean restockOrDefault() {
        return restock == null || restock;
    }
}
