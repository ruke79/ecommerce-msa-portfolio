package com.project.userservice.security.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;
@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponResponse {

    private int totalAfterDiscount;
    private int discount;
}
