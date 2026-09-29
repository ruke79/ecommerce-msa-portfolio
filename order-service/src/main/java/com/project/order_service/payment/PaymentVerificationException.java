package com.project.order_service.payment;

/**
 * 결제를 확인하지 못했다.
 *
 * <p>⚠️ 이 예외가 나면 <b>주문을 결제 완료로 처리해서는 안 된다.</b> "확인 불가"와
 * "결제됨"을 같게 다루는 순간 결제 검증 전체가 무의미해진다.
 *
 * <p>{@code DomainException} 을 상속하므로 컨트롤러가 400 + 이 문구로 바꾼다 —
 * 사용자가 읽을 문장으로 쓸 것.
 */
public class PaymentVerificationException extends com.project.common.domain.exception.DomainException {

    public PaymentVerificationException(String message) {
        super(message);
    }

    public PaymentVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
