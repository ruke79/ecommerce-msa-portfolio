package com.project.common.constants;

/**
 * 사용자가 고른 결제 수단.
 *
 * <p>"누구에게 결제를 확인하러 가는가" 는 {@link PaymentProvider} 다 — 지금은 1:1 로
 * 보이지만 같은 값이 아니다. 카드 결제를 Stripe 로 받을 수도 PayPal 로 받을 수도 있다.
 */
public enum PaymentType {
    PAYPAL, STRIPE, PORTONE, CREDIT_CARD, CASH
}
