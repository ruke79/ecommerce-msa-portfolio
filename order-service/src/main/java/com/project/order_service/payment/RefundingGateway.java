package com.project.order_service.payment;

import java.math.BigDecimal;

/**
 * 환불할 수 있는 제공자.
 *
 * <p>{@link HostedCheckoutGateway} 와 같은 이유로 {@link PaymentGateway} 와 분리했다 —
 * 결제 확인만 되고 환불 API 가 없는 제공자(무통장입금 같은 상태 기계)에게 빈 메서드를
 * 강요하지 않기 위해서다. 지금은 셋(PayPal · Stripe · 포트원) 다 구현한다.
 *
 * <p><b>규칙은 여기 없다.</b> "결제된 주문인가 · 요청자의 것인가 · 이미 환불했는가 ·
 * 배송 전인가 · 환불액이 주문 총액과 같은가" 는 전부 {@code OrderDataService.refundOrder}
 * 가 갖는다. 어댑터는 <b>제공자에게 환불을 요청하고 그 결과를 돌려주는 것</b>만 한다 —
 * 결제 확인과 같은 분리다(docs/49 §2).
 *
 * <p>[JA] 返金できるプロバイダ。
 * {@link HostedCheckoutGateway} と同じ理由で {@link PaymentGateway} と分離した —
 * 決済確認だけができて返金 API のないプロバイダ(銀行振込のような状態機械)に空のメソッドを
 * 強いないためである。いまは三つ(PayPal · Stripe · PortOne)すべてが実装する。
 * <b>ルールはここにない。</b>「決済済みの注文か · 要求者のものか · 既に返金したか ·
 * 配送前か · 返金額が注文総額と同じか」はすべて {@code OrderDataService.refundOrder}
 * が持つ。アダプタは<b>プロバイダに返金を要求してその結果を返すこと</b>だけを行う —
 * 決済確認と同じ分離である(docs/49 §2)。
 */
public interface RefundingGateway extends PaymentGateway {

    /**
     * 환불을 요청한다 — 전액이든 일부든 <b>금액을 명시</b>한다.
     *
     * <p>[JA] 返金を要求する — 全額でも一部でも<b>金額を明示</b>する。
     *
     * @param providerPaymentId 결제 확인 때 저장한 참조(PayPal 주문 id · Stripe 세션 id ·
     *                          포트원 imp_uid). 어댑터가 제공자별로 필요한 것(캡처 id ·
     *                          payment_intent)을 여기서 다시 찾아간다
     *                          [JA] 決済確認時に保存した参照(PayPal 注文 id · Stripe セッション id ·
     *                               PortOne imp_uid)。アダプタがプロバイダごとに必要なもの(キャプチャ id ·
     *                               payment_intent)をここから探し直す
     * @param amount            환불액 — <b>서버가 정한 값</b>({@code RefundPricing}). 요청
     *                          본문에서 오지 않는다
     *                          [JA] 返金額 — <b>サーバが決めた値</b>({@code RefundPricing})。リクエスト
     *                               本文からは来ない
     * @param currency          청구 통화. 제공자가 다른 통화로 응답하면 호출자가 거부한다
     *                          [JA] 請求通貨。プロバイダが別の通貨で応答したら呼び出し側が拒否する
     * @param reason            사유. 제공자 화면·영수증에 남는 문구라 사용자 입력을 그대로
     *                          넣지 않는다 — 호출자가 정리해서 넘긴다
     *                          [JA] 理由。プロバイダの画面・領収書に残る文言なので、ユーザー入力をそのまま
     *                               入れない — 呼び出し側が整えて渡す
     * @param refundKey         이 환불의 멱등 키. 같은 주문에 환불이 여럿(부분 환불)이라
     *                          결제 참조만으로는 구분이 안 된다 — 제공자가 멱등 헤더를
     *                          받으면(PayPal·Stripe) 여기에 싣는다. {@code null} 이면 없이
     *                          [JA] この返金の冪等キー。同じ注文に返金が複数(部分返金)あるので、
     *                               決済参照だけでは区別できない — プロバイダが冪等ヘッダを
     *                               受け付けるなら(PayPal・Stripe)ここに載せる。{@code null} ならなし
     */
    RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason,
            String refundKey);

    /**
     * {@link #refund(String, BigDecimal, String, String, String)} 에 <b>취소 직전의 환불 가능 잔액</b>(청구 통화)을 더한다.
     *
     * <p>멱등 헤더가 없는 제공자(포트원 V1·V2)는 이 값을 함께 보내 <b>같은 환불이 두 번 나가는 것</b>을 막는다 — 제공자가
     * 자기 잔액과 다르면 취소를 거절한다(V1 {@code checksum}, V2 {@code currentCancellableAmount}). 앞선 시도가 제공자에서는
     * 성공했는데 우리 쪽이 실패로 끝나 다시 보내면, 잔액이 이미 줄어 있어 거절된다. 멱등 헤더가 있는 제공자는 무시한다.
     *
     * <p>[JA] {@link #refund(String, BigDecimal, String, String, String)} に<b>取消直前の返金可能残高</b>(請求通貨)を加える。
     * 冪等ヘッダのないプロバイダ(PortOne V1・V2)はこの値を一緒に送り、<b>同じ返金が二度出ること</b>を防ぐ — プロバイダが
     * 自分の残高と異なれば取消を拒絶する(V1 {@code checksum}、V2 {@code currentCancellableAmount})。先の試行がプロバイダでは
     * 成功したのに当方が失敗で終わって再送すると、残高が既に減っているので拒絶される。冪等ヘッダのあるプロバイダは無視する。
     *
     * @param refundableBefore 이 환불 전 청구 통화 잔액(우리 기록). {@code null} 이면 검증 없이
     *                         [JA] この返金前の請求通貨の残高(当方の記録)。{@code null} なら検証なし
     */
    default RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason,
            String refundKey, BigDecimal refundableBefore) {
        return refund(providerPaymentId, amount, currency, reason, refundKey);
    }

    default RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason) {
        return refund(providerPaymentId, amount, currency, reason, null);
    }
}
