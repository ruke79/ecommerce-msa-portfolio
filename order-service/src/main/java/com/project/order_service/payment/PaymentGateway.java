package com.project.order_service.payment;

import com.project.common.constants.PaymentProvider;

/**
 * 결제 제공자 <b>포트</b>.
 *
 * <h3>이 인터페이스가 생긴 이유</h3>
 *
 * <p>예전에는 결제 확인이 {@code OrderDataService.persisitPaypalPayment} 한 메서드 안에
 * 있었고, 그 안에 <b>두 종류의 것이 섞여 있었다</b>:
 *
 * <ul>
 *   <li><b>PayPal 에만 해당하는 것</b> — PayPal REST 로 주문을 조회하고 status 가
 *       {@code COMPLETED} 인지 본다</li>
 *   <li><b>모든 결제에 해당하는 것</b> — 확인된 금액을 <b>DB 의 주문 총액</b>과 대조하고,
 *       통화를 대조하고, 그 결제 참조가 이미 다른 주문에 쓰였는지 막고, 결제 아웃박스에
 *       적는다 (docs/35 의 네 가지 규칙)</li>
 * </ul>
 *
 * <p>둘이 섞여 있으면 <b>결제수단을 하나 추가할 때마다 아래 규칙이 복사된다.</b> 그리고
 * 복사본 중 하나에서 금액 대조를 빠뜨리면 그 수단으로만 결제 없이 주문이 완료된다 —
 * 원래 결함이 정확히 그런 모양이었다.
 *
 * <p>그래서 위쪽만 이 포트로 뽑았다. 아래쪽은 {@link PaymentRecordingService} 하나가
 * 갖고 있고, <b>새 제공자는 그 규칙을 자동으로 상속한다.</b> 결제수단을 추가하는 일은
 * 이제 이 인터페이스를 구현하고 {@link com.project.common.constants.PaymentProvider} 에
 * 값을 더하는 것이 전부다.
 *
 * <p>[JA] 決済プロバイダーの<b>ポート</b>。
 *
 * <h3>[JA] このインターフェースができた理由</h3>
 *
 * <p>[JA] 以前は決済確認が {@code OrderDataService.persisitPaypalPayment} という一つのメソッドの中にあり、
 * そこには<b>二種類のものが混ざっていた</b>:
 *
 * <ul>
 *   <li><b>PayPal だけに当てはまるもの</b> — PayPal REST で注文を照会し、status が
 *       {@code COMPLETED} かどうかを見る</li>
 *   <li><b>すべての決済に当てはまるもの</b> — 確認された金額を<b>DB の注文合計</b>と突き合わせ、
 *       通貨を突き合わせ、その決済参照が既に別の注文に使われていないかを防ぎ、決済アウトボックスに
 *       書く(docs/35 の四つの規則)</li>
 * </ul>
 *
 * <p>[JA] 二つが混ざっていると、<b>決済手段を一つ追加するたびに下の規則が複製される。</b>そして
 * 複製のどれか一つで金額の突き合わせが抜けると、その手段でだけ支払いなしに注文が完了してしまう —
 * 元の欠陥はまさにそういう形だった。
 *
 * <p>[JA] そこで上側だけをこのポートに切り出した。下側は {@link PaymentRecordingService} 一つが
 * 持ち、<b>新しいプロバイダーはその規則を自動的に引き継ぐ。</b>決済手段の追加は、今では
 * このインターフェースを実装して {@link com.project.common.constants.PaymentProvider} に
 * 値を足すだけである。
 */
public interface PaymentGateway {

    /** 이 게이트웨이가 담당하는 제공자. 레지스트리가 이 값으로 고른다. */
    // [JA] このゲートウェイが担当するプロバイダー。レジストリはこの値で選ぶ。
    PaymentProvider provider();

    /**
     * 브라우저가 보고한 결제 참조를 <b>제공자에게 직접 물어</b> 확인한다.
     *
     * @param providerPaymentId 신뢰하지 않는 값. PayPal 주문 id · Stripe 세션 id 등
     * @throws PaymentVerificationException 확인 자체가 불가능했을 때(네트워크·인증 등).
     *         <b>"결제되지 않았다"는 예외가 아니라</b> {@code completed=false} 로 돌려준다 —
     *         둘은 다른 사실이고, 호출자가 다른 문구를 보여 준다.
     *
     * <p>[JA] ブラウザが報告した決済参照を<b>プロバイダーに直接問い合わせて</b>確認する。
     * {@code providerPaymentId} は信頼しない値(PayPal の注文 id · Stripe のセッション id など)。
     * 確認そのものができなかったとき(ネットワーク · 認証など)は {@link PaymentVerificationException} を投げる。
     * <b>「支払われていない」は例外ではなく</b> {@code completed=false} で返す — 二つは別の事実であり、
     * 呼び出し側が別の文言を表示する。
     */
    VerifiedPayment verify(String providerPaymentId);
}
