package com.project.order_service.payment;

import com.project.order_service.model.Order;

/**
 * <b>결제 페이지를 제공자가 호스팅하는</b> 방식의 게이트웨이.
 *
 * <p>PayPal 은 브라우저의 SDK 가 주문을 만들지만, Stripe Checkout 은 <b>서버가 세션을
 * 먼저 만들고</b> 브라우저를 그 URL 로 보낸다. 시작 단계가 있느냐가 유일한 차이라
 * {@link PaymentGateway} 를 확장한다 — 시작 단계가 없는 제공자에게 빈 메서드를 강요하지
 * 않기 위해서다.
 *
 * <p>🔒 <b>금액은 반드시 인자로 받은 {@code order} 에서 읽는다.</b> 브라우저가 보낸
 * 값으로 세션을 만들면 결제 금액 자체를 손님이 정하게 된다 — 뒤에서 대조해도 이미 늦다
 * (사용자는 자기가 승인한 금액을 냈다고 믿는다).
 *
 * <p>[JA] <b>決済ページを提供者がホスティングする</b> 方式のゲートウェイ。
 * PayPal はブラウザの SDK が注文を作るが、Stripe Checkout は <b>サーバーがセッションを先に作り</b> ブラウザをその URL に送る。
 * 開始段階があるかどうかが唯一の違いなので {@link PaymentGateway} を拡張する — 開始段階のない提供者に空のメソッドを強いないためだ。
 * 🔒 <b>金額は必ず引数で受けた {@code order} から読む。</b> ブラウザが送った値でセッションを作ると決済金額そのものを客が決めることになる
 * — 後で照合してももう遅い(ユーザーは自分が承認した金額を払ったと信じている)。
 */
public interface HostedCheckoutGateway extends PaymentGateway {

    /**
     * 결제 페이지를 하나 연다.
     *
     * <p>[JA] 決済ページを 1 つ開く。
     *
     * @param order      결제할 주문. <b>금액의 유일한 근거다</b>
     *     [JA] 決済する注文。<b>金額の唯一の根拠だ</b>
     * @param returnUrl  결제 후 브라우저가 돌아올 주소
     *     [JA] 決済後にブラウザが戻るアドレス
     * @param cancelUrl  결제를 취소했을 때 돌아올 주소
     *     [JA] 決済を取り消したときに戻るアドレス
     */
    CheckoutSession startCheckout(Order order, String returnUrl, String cancelUrl);
}
