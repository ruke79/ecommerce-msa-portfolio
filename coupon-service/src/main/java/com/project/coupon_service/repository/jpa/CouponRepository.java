package com.project.coupon_service.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.project.common.constants.coupon.CouponStatus;
import com.project.coupon_service.model.Coupon;

import jakarta.persistence.LockModeType;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {
    
    // Find by couponId with pessimistic lock
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Coupon> findById(Long id);

    // Find by name and user with pessimistic lock
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Coupon> findByNameAndUserIdAndCouponStatus(String name, long userId, CouponStatus couponStatus);

     // Find by user ID and status with pessimistic lock
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<List<Coupon>> findByUserIdAndCouponStatus(long userId, CouponStatus couponStatus);

    
    boolean existsByNameAndUserId(String name, long userId);
    
}
