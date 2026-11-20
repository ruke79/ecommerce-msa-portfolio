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
 *
 * <p>[JA] 返品入庫処理リクエストの本文(管理者)。
 * {@link RefundRequest} と同じ原則 — <b>どの注文</b>と<b>在庫に戻すか</b>だけである。数量 · 商品は本文にない。戻す数量は DB の注文品目そのままで、本文に置くとその値を信じることになる。
 */
@Getter
@Setter
@NoArgsConstructor
public class ReturnReceivedRequest {

    private String trackingId;

    /**
     * 재고로 되돌릴 것인가. 기본 {@code true}. 파손·오염으로 다시 팔 수 없으면 {@code false} —
     * 입고는 기록되지만 재고는 그대로다.
     *
     * <p>[JA] 在庫に戻すか。デフォルト {@code true}。破損 · 汚損で再販できなければ {@code false} — 入庫は記録されるが在庫はそのまま。
     */
    private Boolean restock;

    /** 부분 반품 — 품목과 수량. 비어 있으면 아직 돌아오지 않은 전부다. ／ [JA] 部分返品 — 品目と数量。空ならまだ戻っていない全部である。 */
    private java.util.List<OrderItemQtyRequest> items;

    public boolean restockOrDefault() {
        return restock == null || restock;
    }
}
