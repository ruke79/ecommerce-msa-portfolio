package com.project.order_service.payment;

import java.math.BigDecimal;

import com.project.common.constants.PaymentProvider;

/**
 * <b>서버가 결제 제공자에게 직접 물어서 확인한</b> 결제 내역.
 *
 * <p>브라우저가 보고한 값이 아니다 — 그것을 믿었던 것이 docs/35 결함의 출발점이었다.
 *
 * @param amount {@code null} 이면 <b>검증이 꺼진 모드</b>다. 그때는 금액을 대조할 수 없다
 *               (기동 시점에 경고를 찍는다).
 */
public record VerifiedPayment(
        PaymentProvider provider,
        String providerPaymentId,
        boolean completed,
        String rawStatus,
        BigDecimal amount,
        String currency,
        /**
         * 제공자가 그 결제에 붙여 둔 <b>우리 주문 참조</b>(trackingId) — 결제를 만들 때 실은 값(Stripe {@code client_reference_id} ·
         * 포트원 {@code merchant_uid} · V2 {@code customData}). 제공자가 모르면 null(PayPal — 결제창이 주문 참조를 싣지 않는다).
         * recordPayment 가 이것이 그 주문인지 대조한다.
         * [JA] 提供者がその決済に付けた当方の注文参照。recordPayment がその注文か照合する。
         */
        String orderReference) {

    /** 주문 참조를 모르는 제공자(PayPal) · 테스트. */
    public VerifiedPayment(PaymentProvider provider, String providerPaymentId, boolean completed, String rawStatus,
            BigDecimal amount, String currency) {
        this(provider, providerPaymentId, completed, rawStatus, amount, currency, null);
    }
}
