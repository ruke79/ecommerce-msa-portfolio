package com.project.order_service.client;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.project.order_service.config.FeignConfig;

/**
 * 쿠폰이 얼마를 깎는지 물어본다.
 *
 * <p>주문을 만들 때 총액을 <b>서버에서</b> 계산하기 위한 것이다(docs/40 #10). 예전에는
 * 브라우저가 보낸 {@code total} 을 그대로 저장했고, PayPal 검증은 그 값과 결제액을
 * 대조하기만 했다 — 즉 DevTools 로 총액을 1로 바꾸면 1달러 결제가 <b>정상적으로</b>
 * 검증을 통과했다.
 *
 * <p>🔴 <b>견적이지 사용 처리가 아니다.</b> 주문 생성이 쿠폰을 소모하면 결제에 실패하거나
 * 사용자가 결제 화면에서 이탈했을 때 쓰지도 않은 쿠폰이 사라진다. 쿠폰 사용 이벤트는
 * 원래대로 적용 시점({@code /applycoupon})에만 발행된다.
 *
 * <p>누구의 견적인가는 <b>사용자 id</b> 로 묻는다(docs/48 §6-⑥). 예전에는 이메일을 넘겼고 coupon 이 그 이메일로
 * user-service 에 id 를 다시 물었다. {@code User-Role} 등 신원 헤더는 게이트웨이만 붙이므로 이 호출에는 없고,
 * 저쪽은 그것을 "내부 호출" 로 읽는다.
 */
@FeignClient(name = "coupon-service", url = "${feign.coupon-url}", configuration = FeignConfig.class)
public interface CouponServiceClient {

    @PostMapping("api/coupon/internal/quote")
    Quote quote(@RequestParam("userId") Long userId, @RequestParam("couponName") String couponName);

    /**
     * 결제를 시작한다 — 이 주문이 쿠폰을 {@code holdSeconds} 동안 잡는다(coupon V7). 결과는 {@code CLAIMED · NOT_FOUND · USED ·
     * IN_PAYMENT_ELSEWHERE} 중 하나. 판단은 {@link com.project.order_service.service.OrderCouponClaim}.
     */
    @PostMapping("api/coupon/internal/payment-claim")
    PaymentClaim claimForPayment(@RequestParam("userId") Long userId, @RequestParam("couponName") String couponName,
            @RequestParam("orderId") Long orderId, @RequestParam("holdSeconds") long holdSeconds);

    record PaymentClaim(String result) {
    }

    /**
     * coupon-service 의 {@code CouponResponse} 중 금액 계산에 필요한 부분.
     *
     * <p>저쪽 DTO 를 그대로 가져다 쓰지 않는 이유는 모듈 경계다 — coupon-service 의 내부
     * 표현이 바뀌어도 여기가 깨지지 않는다. 모르는 필드는 무시된다.
     *
     * @param totalAfterDiscount 할인 후 총액
     * @param discountAmount     실제로 깎인 금액
     * @param message            비어 있지 않으면 <b>적용되지 않았다</b>는 뜻이고 그 사유다
     */
    record Quote(BigDecimal totalAfterDiscount, BigDecimal discountAmount, String message) {

        /** 실제로 할인이 적용됐는가. 사유가 붙어 있으면 적용되지 않은 것이다. */
        public boolean applied() {
            return (message == null || message.isBlank()) && discountAmount != null;
        }
    }
}
