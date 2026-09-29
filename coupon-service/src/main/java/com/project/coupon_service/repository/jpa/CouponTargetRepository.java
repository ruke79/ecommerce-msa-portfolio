package com.project.coupon_service.repository.jpa;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.coupon_service.model.CouponTarget;

@Repository
public interface CouponTargetRepository extends JpaRepository<CouponTarget, Long> {

    List<CouponTarget> findByCouponId(Long couponId);

    void deleteByCouponId(Long couponId);
}
