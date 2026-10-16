package com.project.coupon_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.common.message.dto.request.CouponRollbackRequest;
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
            // 되돌릴 것이 없다 — 쓰이지 않았거나(결제 전 취소 · 결제 실패) 다른 주문이 썼다. 정상이다.
            // 🐛 예전에는 여기서 던져 재시도 뒤 DLT 로 갔다: 쿠폰을 고른 주문의 결제 실패마다 DLT 한 건과 경보(KafkaDeadLetterPublished).
            //    다시 시도해도 결과가 같다. [JA] 戻すものがない — 正常。以前は例外で再試行の後 DLT に行っていた。
            log.info("쿠폰 보상 — 되돌릴 것 없음. orderId={}", request.getOrderId());
            return;
        }
        log.info("Coupon is rollbacked in coupon database with id: {}", coupon.getCouponId());
    }
}
