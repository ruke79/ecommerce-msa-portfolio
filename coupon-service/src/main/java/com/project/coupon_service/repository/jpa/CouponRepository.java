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
    /**
     * 결제 확정 · 보상 — 이름 · 사용자로(유니크) 잠그고 읽는다. 같은 쿠폰의 두 주문 결제가 동시에 와도 하나만 NOT_USED 를 본다.
     * [JA] 決済確定 · 補償 — 名前 · ユーザーでロックして読む。同じクーポンの二つの注文が同時でも一つだけが NOT_USED を見る。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Coupon c where c.name = :name and c.userId = :userId")
    Optional<Coupon> findForUpdate(@org.springframework.data.repository.query.Param("name") String name,
            @org.springframework.data.repository.query.Param("userId") long userId);

    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<List<Coupon>> findByUserIdAndCouponStatus(long userId, CouponStatus couponStatus);

    
    boolean existsByNameAndUserId(String name, long userId);
    
}
