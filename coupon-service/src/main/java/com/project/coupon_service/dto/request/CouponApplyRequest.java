package com.project.coupon_service.dto.request;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.project.coupon_service.dto.CouponDto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class CouponApplyRequest {

    BigDecimal totalPrice;

    private String couponName;

    // 신원 필드(userEmail)는 없다 — 누구의 쿠폰인가는 게이트웨이가 검증한 User-Id 가 정한다. 예전에는 본문의 이메일을
    // 믿어 남의 쿠폰이 적용됐다(BOLA). 옛 클라이언트가 보내도 무시한다(ignoreUnknown).
}
