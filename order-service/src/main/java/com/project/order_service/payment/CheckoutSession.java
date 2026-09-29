package com.project.order_service.payment;

/**
 * 제공자가 열어 준 결제 페이지.
 *
 * @param sessionId   나중에 이 결제를 확인할 때 쓸 참조
 * @param redirectUrl 브라우저를 보낼 주소
 */
public record CheckoutSession(String sessionId, String redirectUrl) {
}
