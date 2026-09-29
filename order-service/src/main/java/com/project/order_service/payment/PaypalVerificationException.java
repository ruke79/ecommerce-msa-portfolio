package com.project.order_service.payment;

/**
 * 결제를 확인하지 못했다. 이 예외가 나오면 <b>주문을 결제 완료로 만들면 안 된다.</b>
 *
 * <p>실패는 두 종류인데 호출자 입장에서는 똑같이 다뤄야 한다:
 * <ul>
 *   <li><b>확실한 거부</b> — PayPal 이 "그런 주문 없음" 또는 "아직 캡처되지 않음"이라고 답했다</li>
 *   <li><b>확인 불가</b> — PayPal 에 닿지 못했다(네트워크·토큰 발급 실패)</li>
 * </ul>
 *
 * 후자를 "일단 통과"로 처리하고 싶은 유혹이 있는데, 그러면 PayPal 을 잠깐 막는 것만으로
 * 검증 전체가 무력화된다. <b>확인되지 않으면 거부한다</b>(fail closed).
 */
/**
 * ⚠️ 이제 {@link PaymentVerificationException} 을 상속한다 — 결제 확인 실패는 제공자와
 * 무관하게 한 가지 사실이고, 호출부가 제공자마다 다른 예외를 잡아야 할 이유가 없다.
 * PayPal 전용 코드가 남아 있는 동안만 유지되는 이름이다.
 */
public class PaypalVerificationException extends PaymentVerificationException {

    public PaypalVerificationException(String message) {
        super(message);
    }

    public PaypalVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
