package com.project.common.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 주문 품목 하나와 수량 — 부분 환불·부분 반품의 단위.
 *
 * <p>금액은 없다. 환불액은 서버가 주문의 단가·할인·배송비에서 정한다(docs/49 §10).
 * 품목은 {@code OrderProduct} 의 id 로 가리킨다 — 상품 id 가 아니다(같은 상품이 사이즈만
 * 다르게 두 줄일 수 있다).
 *
 * <p>[JA] 注文品目一つと数量 — 部分返金 · 部分返品の単位。
 * 金額はない。返金額はサーバーが注文の単価 · 割引 · 送料から決める(docs/49 §10)。品目は {@code OrderProduct} の id で指す — 商品 id ではない(同じ商品がサイズ違いで二行になりうる)。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemQtyRequest {

    private Long orderProductId;

    private int qty;
}
