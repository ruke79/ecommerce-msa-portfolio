package com.project.order_service.payment;

import java.math.BigDecimal;

import lombok.extern.slf4j.Slf4j;

/**
 * 검증을 건너뛴다. <b>자격증명 없이 도는 로컬 개발·테스트 전용</b>이다.
 *
 * <p>이 빈이 선택되면 결제는 예전과 똑같이 "브라우저가 그렇다니까 그런 것"이 된다.
 * 그래서 기동할 때 {@link com.project.order_service.config.PaypalConfig} 가 WARN 을 찍고,
 * 여기서도 호출될 때마다 남긴다 — 운영에서 이 로그가 보이면 설정이 잘못된 것이다.
 *
 * <p>기본값으로 두지 않은 것이 핵심이다. {@code paypal.verification.mode=disabled} 를
 * <b>명시해야만</b> 선택된다. 메일·SMS 의 logging 기본값(docs/29)과 방향이 반대인데,
 * 이유가 있다 — 메일은 "실수로 보내는 것"이 위험하지만 결제는 "실수로 통과시키는 것"이
 * 위험하다. 안전한 기본값의 방향이 서로 다르다.
 */
@Slf4j
public class DisabledPaypalOrderVerifier implements PaypalOrderVerifier {

    @Override
    public VerifiedPaypalOrder verify(String paypalOrderId) {
        log.warn("⚠️ PayPal 검증이 꺼져 있습니다 — 브라우저가 보고한 결제를 그대로 신뢰합니다 "
                + "(paypalOrderId={}). 운영이라면 즉시 paypal.verification.mode 를 고치세요.",
                paypalOrderId);
        // 금액 대조를 통과시키기 위해 null 을 돌려주지 않는다. 호출자가 금액 비교를
        // 건너뛰도록 status 만 COMPLETED 로 두고 금액은 '검증 안 함'을 뜻하는 null 로 둔다.
        return new VerifiedPaypalOrder(paypalOrderId, VerifiedPaypalOrder.COMPLETED, null, null);
    }

    /**
     * 검증을 끈 모드에는 실제 결제가 없으니 돌려줄 돈도 없다. 그런데 "환불됐다" 고
     * 답하면 주문이 REFUNDED 로 바뀌어 <b>돈이 돌아간 것처럼 보인다.</b> 거부한다.
     */
    @Override
    public RefundedPaypalOrder refund(String paypalOrderId, java.math.BigDecimal amount, String currency,
            String refundKey) {
        throw new PaypalVerificationException(
                "PayPal 검증이 꺼진 모드(paypal.verification.mode=disabled)에서는 환불할 수 없습니다.");
    }
}
