package com.project.common.message.dto.request;

import java.io.Serializable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;


@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CartEmptyRequest implements Serializable {
    Long userId;
    boolean emptyCart;
    // 사용자 액세스 토큰은 싣지 않는다(docs/54 §10.1). cart 는 캐시 키가 사용자 id 라 user-service 를 부를 일이 없다.
    /**
     * 뺄 품목. 비어 있으면 전부 비운다(옛 발행자 호환).
     *
     * <p>🐛 "이 사용자의 장바구니를 비워라" 는 이벤트가 결제 뒤 비동기로 도착하는 사이에 손님이
     * 새로 담은 물건까지 지웠다. 주문의 품목만 싣는다(OrderSaga.publishCartEmptyState).
     */
    java.util.List<CartEmptyItem> items = new java.util.ArrayList<>();

    public CartEmptyRequest(Long userId, boolean emptyCart) {
        this(userId, emptyCart, new java.util.ArrayList<>());
    }

    public java.util.List<CartEmptyItem> getItems() {
        return items == null ? java.util.List.of() : items;
    }
}
