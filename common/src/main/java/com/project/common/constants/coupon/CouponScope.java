package com.project.common.constants.coupon;

/**
 * 쿠폰이 어디에 적용되는가.
 *
 * <p>예전에는 이 개념 자체가 없었다. 쿠폰은 {@code userId · name · 기간 · 할인율} 뿐이었고,
 * 적용하면 <b>장바구니 총액 전체</b>에 할인율이 곱해졌다. "패션 카테고리 10% 할인" 같은
 * 기획을 표현할 방법이 없었고, 한 장으로 장바구니 전체가 깎였다.
 *
 * <p>{@link #ALL} 은 예전 동작 그대로다 — 기존 쿠폰은 전부 여기에 들어간다.
 */
public enum CouponScope {

    /** 장바구니 전체. 제약 없음(기존 동작). */
    ALL,

    /** 지정한 카테고리에 속한 상품에만. 대상은 coupon_target 에 카테고리 id 로 들어간다. */
    CATEGORY,

    /** 지정한 상품에만. 대상은 coupon_target 에 상품 id 로 들어간다. */
    PRODUCT
}
