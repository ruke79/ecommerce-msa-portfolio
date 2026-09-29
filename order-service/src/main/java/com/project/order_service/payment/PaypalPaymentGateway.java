package com.project.order_service.payment;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentProvider;

import lombok.RequiredArgsConstructor;

/**
 * PayPal 어댑터.
 *
 * <p>{@link PaypalOrderVerifier} 를 <b>감싸기만</b> 한다 — 잘 돌던 PayPal 검증 경로를
 * 포트를 만들면서 건드리지 않기 위해서다(docs/49 §2). 검증 모드(http/disabled)를 고르는
 * 것도 그대로 {@code PaypalConfig} 가 한다.
 *
 * <p>[JA] PayPal アダプタ。
 *
 * <p>{@link PaypalOrderVerifier} を<b>ラップするだけ</b>である — 問題なく動いていた PayPal の検証経路を、
 * ポートを作る際に触らないためだ(docs/49 §2)。検証モード(http/disabled)の選択も
 * そのまま {@code PaypalConfig} が行う。
 */
@Component
@RequiredArgsConstructor
public class PaypalPaymentGateway implements RefundingGateway {

    private final PaypalOrderVerifier verifier;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PAYPAL;
    }

    @Override
    public VerifiedPayment verify(String providerPaymentId) {
        VerifiedPaypalOrder verified = verifier.verify(providerPaymentId);
        return new VerifiedPayment(
                PaymentProvider.PAYPAL,
                verified.paypalOrderId(),
                verified.isCompleted(),
                verified.status(),
                verified.amount(),
                verified.currency());
    }

    @Override
    public RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason,
            String refundKey) {
        // PayPal 환불 API 에는 사유 필드가 없다(note_to_payer 는 있지만 사용자에게 보이는
        // 문구라 넣지 않는다). 사유는 우리 로그·주문에만 남는다.
        // [JA] PayPal の返金 API には理由フィールドがない(note_to_payer はあるが、ユーザーに見える
        //      文言なので入れない)。理由はこちらのログ・注文にだけ残る。
        RefundedPaypalOrder refunded = verifier.refund(providerPaymentId, amount, currency, refundKey);
        return new RefundResult(
                PaymentProvider.PAYPAL,
                refunded.refundId(),
                refunded.isAccepted(),
                refunded.status(),
                refunded.amount(),
                refunded.currency());
    }
}
