package com.project.coupon_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.common.message.dto.request.CouponRollbackRequest;
import com.project.coupon_service.exception.CustomDomainException;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.service.CouponService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponRollbackMessageListener {

    private final CouponService couponService;

    @Transactional
    public void couponRollback(CouponRollbackRequest request) {

        Coupon coupon = couponService.couponRollback(request);

        if (coupon == null) {
            log.error("Coupon could not be rollbacked in coupon database with name: {}", request.getCouponName());
            throw new CustomDomainException("Coupon could not be created in coupon database with name " +
            request.getCouponName());
        }
        log.info("Coupon is rollbacked in coupon database with id: {}", coupon.getCouponId());
    }
}
