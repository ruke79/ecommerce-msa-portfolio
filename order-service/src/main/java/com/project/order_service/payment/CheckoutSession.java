package com.project.order_service.payment;

/**
 * 제공자가 열어 준 결제 페이지.
 *
 * <p>[JA] 提供者が開いた決済ページ。
 *
 * @param sessionId   나중에 이 결제를 확인할 때 쓸 참조
 *     [JA] 後でこの決済を確認するときに使う参照
 * @param redirectUrl 브라우저를 보낼 주소
 *     [JA] ブラウザを送るアドレス
 */
public record CheckoutSession(String sessionId, String redirectUrl) {
}
