package com.project.common.constants.coupon;

public enum CouponStatus {
    NOT_USED, USED
    // EXPIRED 는 없앴다 — 어떤 코드도 그 값을 쓰지 않았다. 만료는 상태가 아니라 기간(Coupon.endDate)에서 읽을 때 정한다
    // (CouponDto.convertToDto 의 usable). 아무 행도 갖지 않는 상태 값은 언젠가 누군가 그것으로 거르거나 세게 되고, 조용히 틀린다.
}
