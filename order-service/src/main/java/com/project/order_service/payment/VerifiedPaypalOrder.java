package com.project.order_service.payment;

import java.math.BigDecimal;

/**
 * PayPal 이 확인해 준 결제 내역. <b>브라우저가 보낸 값이 아니라 서버가 조회한 값</b>이다.
 *
 * @param paypalOrderId PayPal 주문 ID
 * @param status        PayPal 이 보고한 상태. 결제 완료는 {@code COMPLETED} 뿐이다
 *                      ({@code APPROVED} 는 승인만 된 상태로, 캡처 전이라 돈이 오지 않았다)
 * @param amount        실제 결제 금액
 * @param currency      통화. 금액만 비교하면 59.98 USD 를 59.98 KRW 로 바꿔치기할 수 있다
 */
public record VerifiedPaypalOrder(
        String paypalOrderId,
        String status,
        BigDecimal amount,
        String currency) {

    public static final String COMPLETED = "COMPLETED";

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }
}
