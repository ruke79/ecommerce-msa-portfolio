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
        String currency) {
}
