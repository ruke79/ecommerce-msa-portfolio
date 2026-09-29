package com.project.coupon_service.dto.response;

import java.math.BigDecimal;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponResponse {

    private BigDecimal totalAfterDiscount;

    /** 할인율(%) — 쿠폰에 적힌 값 그대로다. 실제로 깎인 금액은 {@link #discountAmount}. */
    private int discount;

    private String message;

    /**
     * 실제로 깎인 금액.
     *
     * <p>제약 있는 쿠폰이 생기면서 <b>할인율만으로는 얼마가 깎였는지 알 수 없게 됐다.</b>
     * "10% 쿠폰"이라도 장바구니 10만원 중 대상 상품이 2만원뿐이면 깎이는 건 2천원이다.
     * 화면이 그 차이를 설명할 수 있어야 한다.
     */
    private BigDecimal discountAmount;

    /** 할인율이 곱해진 금액. 제약 없는 쿠폰이면 장바구니 총액과 같다. */
    private BigDecimal eligibleSubtotal;

    /** 할인이 적용된 장바구니 줄 수. 0이면 대상 상품이 장바구니에 없다는 뜻이다. */
    private int eligibleItemCount;
}
