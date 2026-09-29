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
