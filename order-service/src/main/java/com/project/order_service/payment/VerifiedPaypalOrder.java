package com.project.order_service.payment;

import java.math.BigDecimal;

/**
 * PayPal 이 확인해 준 결제 내역. <b>브라우저가 보낸 값이 아니라 서버가 조회한 값</b>이다.
 *
 * <p>[JA] PayPal が確認した決済内容。<b>ブラウザが送った値ではなくサーバが照会した値</b>である。
 *
 * @param paypalOrderId PayPal 주문 ID
 *                      [JA] PayPal 注文 ID
 * @param status        PayPal 이 보고한 상태. 결제 완료는 {@code COMPLETED} 뿐이다
 *                      ({@code APPROVED} 는 승인만 된 상태로, 캡처 전이라 돈이 오지 않았다)
 *                      [JA] PayPal が報告した状態。決済完了は {@code COMPLETED} だけである
 *                           ({@code APPROVED} は承認だけの状態で、キャプチャ前なのでお金は来ていない)
 * @param amount        실제 결제 금액
 *                      [JA] 実際の決済金額
 * @param currency      통화. 금액만 비교하면 59.98 USD 를 59.98 KRW 로 바꿔치기할 수 있다
 *                      [JA] 通貨。金額だけ比べると 59.98 USD を 59.98 KRW にすり替えられる
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
