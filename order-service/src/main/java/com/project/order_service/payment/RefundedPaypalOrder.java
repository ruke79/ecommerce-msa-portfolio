package com.project.order_service.payment;

import java.math.BigDecimal;

/** PayPal 환불 응답 — {@code POST /v2/payments/captures/{id}/refund} 의 요약. */
public record RefundedPaypalOrder(
        String refundId,
        String status,
        BigDecimal amount,
        String currency) {

    public static final String COMPLETED = "COMPLETED";
    public static final String PENDING = "PENDING";

    /** 받아들여졌는가. PENDING 은 은행 처리 중이라는 뜻이고 돈은 돌아간다. */
    public boolean isAccepted() {
        return COMPLETED.equals(status) || PENDING.equals(status);
    }
}
