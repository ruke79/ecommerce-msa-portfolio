package com.project.coupon_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
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
    public void completeCoupon(PaymentResponse response) {

        // 쿠폰을 쓴 결제 완료만 — 실패 · 취소(환불)는 이 토픽에도 온다. 환불 때의 쿠폰 복원은 사가가 따로 한다(OrderSaga).
        // 🐛 예전: 쿠폰이 없는 결제(대부분)도 아래까지 내려가 null 을 받고 예외 → 재시도 3번 뒤 DLT 로 쌓였다.
        if (response.getPaymentStatus() != PaymentStatus.COMPLETED
                || response.getCouponName() == null || response.getCouponName().isBlank()) {
            return;
        }

        // 멱등성 (docs/12 §2). Kafka 는 at-least-once 라 같은 결제 응답이 다시 올 수 있다.
        // 결제 1건당 쿠폰 사용은 1회여야 하므로 paymentId 가 자연 멱등 키다.
        String eventId = response.getPaymentId() != null ? "payment-" + response.getPaymentId() : null;
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            log.info("Duplicate payment event '{}' — coupon already processed, skipping", eventId);
            return;
        }

        Coupon coupon = couponService.updateCouponStatus(response);
        if (coupon == null) {
            // 쓸 수 있는(NOT_USED) 쿠폰이 없다 — 이미 다른 주문에서 쓰였거나 주인이 다르다. 다시 시도해도 결과는 같으므로
            // DLT 로 보내지 않고 기록만 남긴다(쿠폰 이름은 사용자가 고른 값이라 로그에 싣지 않는다).
            log.error("결제는 완료됐지만 사용할 수 있는 쿠폰이 없다 — orderId={}, customerId={}",
                    response.getOrderId(), response.getCustomerId());
        } else {
            log.info("Coupon is used in coupon database with id: {}", coupon.getCouponId());
        }
        if (eventId != null) {
            // 쿠폰 상태 변경과 같은 트랜잭션에 기록 → 둘 다 반영되거나 둘 다 롤백(원자성).
            processedEventRepository.save(new ProcessedEvent(eventId));
        }
    }

}
