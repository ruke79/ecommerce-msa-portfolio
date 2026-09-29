package com.project.coupon_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.coupon_service.exception.CustomDomainException;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.model.ProcessedEvent;
import com.project.coupon_service.repository.jpa.ProcessedEventRepository;
import com.project.coupon_service.service.CouponService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResponseMessageListener {

    private final CouponService couponService;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void completeCoupon(PaymentResponse response, String token) {

        // 멱등성 (docs/12 §2). Kafka 는 at-least-once 라 같은 결제 응답이 다시 올 수 있는데,
        // 그때 updateCouponStatus 는 NOT_USED 인 쿠폰을 못 찾아 null 을 돌려주고 아래에서 예외가
        // 던져진다 → **정상적인 재전달이 DLT 로 쌓여 가짜 장애처럼 보인다.**
        // 결제 1건당 쿠폰 사용은 1회여야 하므로 paymentId 가 자연 멱등 키다.
        String eventId = response.getPaymentId() != null ? "payment-" + response.getPaymentId() : null;
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            log.info("Duplicate payment event '{}' — coupon already processed, skipping", eventId);
            return;
        }

        Coupon coupon = couponService.updateCouponStatus(response, token);
        
        if (coupon == null) {
            log.error("Coupon could not be used in coupon database with name: {}", response.getCouponName());
            throw new CustomDomainException("Coupon could not be used in coupon database with name " +
            response.getCouponName());
        }
        if (eventId != null) {
            // 쿠폰 상태 변경과 같은 트랜잭션에 기록 → 둘 다 반영되거나 둘 다 롤백(원자성).
            processedEventRepository.save(new ProcessedEvent(eventId));
        }
        log.info("Coupon is used in coupon database with id: {}", coupon.getCouponId());
        
    }

}
