package com.project.common.message.dto.request;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니에서 뺄 한 줄 — 주문의 품목(상품·사이즈·색상·수량). {@link CartEmptyRequest#getItems()}.
 *
 * <p>장바구니 줄의 id 가 아니라 상품 좌표로 맞춘다. 주문은 장바구니 줄 id 를 갖고 있지 않고,
 * 같은 상품·사이즈·색상이면 같은 줄이다(cart-service 의 담기 규칙).
 *
 * <p>[JA] カートから外す一行 — 注文の品目(商品 · サイズ · 色 · 数量)。{@link CartEmptyRequest#getItems()}。
 * カート行の id ではなく商品の座標で合わせる。注文はカート行 id を持っておらず、同じ商品 · サイズ · 色なら同じ行である(cart-service の追加規則)。
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartEmptyItem implements Serializable {
    Long productId;
    String size;
    Long colorId;
    int qty;
}
