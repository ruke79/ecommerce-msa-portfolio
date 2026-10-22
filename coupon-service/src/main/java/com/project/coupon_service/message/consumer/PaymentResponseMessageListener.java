package com.project.coupon_service.message.consumer;


import org.springframework.stereotype.Service;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
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

    /** 한 쿠폰으로 두 주문이 할인을 받은 수 — Prometheus {@code coupon_double_use_total}, 경보 CouponDoubleUse. */
    public static final String DOUBLE_USE_METRIC = "coupon.double.use";

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동 때 0 으로 만든다(B-101) — 처음 늘 때 생기면 첫 값이 이미 1 이라 increase() 가
     * 첫 사고를 0 으로 본다(2026-10-22 실습: FAILED 3건에도 OrderOutboxRowFailed 가 울리지 않았다).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る — 初回の増加で生まれると最初の値が 1 で、increase() が初回を 0 と見る。
     */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        io.micrometer.core.instrument.Metrics.counter(DOUBLE_USE_METRIC);
    }

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

        CouponService.UseOutcome outcome = couponService.useForOrder(response);
        switch (outcome) {
            case USED -> log.info("쿠폰 사용 처리 — orderId={}", response.getOrderId());
            case ALREADY_THIS_ORDER -> log.info("쿠폰은 이미 이 주문이 썼다(재전달) — orderId={}", response.getOrderId());
            case USED_BY_ANOTHER_ORDER -> {
                // 한 쿠폰으로 두 주문이 할인을 받았다 — 다시 시도해도 결과는 같으므로 DLT 로 보내지 않고 세어 경보한다
                // (CouponDoubleUse). 쿠폰 이름은 사용자가 고른 값이라 로그에 싣지 않는다.
                // [JA] 一つのクーポンで二つの注文が割引を受けた — 再試行しても同じなので DLT には送らず、数えて警報する。
                io.micrometer.core.instrument.Metrics.counter(DOUBLE_USE_METRIC).increment();
                log.error("쿠폰 이중 사용 — 다른 주문이 이미 쓴 쿠폰으로 결제가 완료됐다. orderId={}, customerId={}",
                        response.getOrderId(), response.getCustomerId());
            }
            case NOT_FOUND -> log.error("결제는 완료됐지만 그 쿠폰이 없다 — orderId={}, customerId={}",
                    response.getOrderId(), response.getCustomerId());
        }
        if (eventId != null) {
            // 쿠폰 상태 변경과 같은 트랜잭션에 기록 → 둘 다 반영되거나 둘 다 롤백(원자성).
            processedEventRepository.save(new ProcessedEvent(eventId));
        }
    }

}
