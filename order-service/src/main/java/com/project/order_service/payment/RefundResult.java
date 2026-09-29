package com.project.order_service.payment;

import java.math.BigDecimal;

import com.project.common.constants.PaymentProvider;

/**
 * 제공자가 돌려준 환불 결과 — 제공자 중립.
 *
 * @param refundId  제공자의 환불 참조. 영수증·분쟁 대응에 쓰이므로 주문에 저장한다
 * @param accepted  제공자가 환불을 <b>받아들였는가</b>. 카드 환불은 보통 즉시 완료지만
 *                  PayPal 은 {@code PENDING} 으로 답할 수 있다 — 받아들여졌으면 돈은
 *                  돌아간다. 거절(실패)만 false 다
 * @param rawStatus 제공자가 준 상태 문자열 그대로 — 로그·디버깅용
 * @param amount    제공자가 환불했다고 말한 금액. 호출자가 주문 총액과 대조한다
 * @param currency  대문자 ISO 코드
 */
public record RefundResult(
        PaymentProvider provider,
        String refundId,
        boolean accepted,
        String rawStatus,
        BigDecimal amount,
        String currency) {
}
