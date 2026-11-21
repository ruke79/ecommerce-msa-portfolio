package com.project.order_service.payment;

/**
 * PayPal 이 <b>실제로</b> 그 주문을 결제 완료 처리했는지 확인한다.
 *
 * <p>왜 필요한가. 이 서비스의 결제 반영 경로는 브라우저가 보낸 값을 그대로 믿고 있었다:
 *
 * <pre>
 *   POST /api/order/payment/paypal
 *   { "trackingId": "...", "paypalOrderId": "PP-1", "amounts": 59.98,
 *     "paymentStatus": "COMPLETED", "orderStatus": "NOT_PROCESSED" }
 * </pre>
 *
 * 서버는 이 중 무엇도 검증하지 않고 아웃박스에 넣었다. 즉 <b>결제하지 않고도 결제 완료를
 * 만들 수 있었다</b> — 위 요청을 curl 로 보내면 끝이다. 금액도 클라이언트가 정했다.
 *
 * <p>PayPal JS SDK 가 브라우저에서 캡처를 마쳤다는 사실은 <b>서버 입장에서 증거가 아니다.</b>
 * 브라우저는 신뢰 경계 밖이고, SDK 가 돌려준 {@code details.id} 는 그저 문자열이다.
 * 유일하게 믿을 수 있는 것은 <b>서버가 PayPal 에 직접 물어본 답</b>이다.
 *
 * <p>구현은 둘이다:
 * <ul>
 *   <li>{@link HttpPaypalOrderVerifier} — PayPal Orders API 에 실제로 조회한다(운영 기본값)</li>
 *   <li>{@link DisabledPaypalOrderVerifier} — 검증을 건너뛴다. 자격증명 없이 도는 로컬 개발용이고,
 *       기동할 때마다 경고를 찍는다. <b>운영에서 켜면 안 된다.</b></li>
 * </ul>
 *
 * <p>[JA] PayPal が<b>実際に</b>その注文を決済完了にしたかを確認する。
 * なぜ必要か。このサービスの決済反映経路は、ブラウザが送った値をそのまま信じていた
 * (上の POST /api/order/payment/paypal)。サーバはそのどれも検証せずアウトボックスに入れた。
 * つまり<b>決済せずに決済完了を作れた</b> — 上のリクエストを curl で送れば終わりである。金額もクライアントが決めていた。
 * PayPal JS SDK がブラウザでキャプチャを終えたという事実は<b>サーバにとって証拠ではない。</b>
 * ブラウザは信頼境界の外であり、SDK が返した {@code details.id} はただの文字列である。
 * 唯一信じられるのは<b>サーバが PayPal に直接問い合わせた答え</b>である。
 * 実装は二つ: {@link HttpPaypalOrderVerifier} — PayPal Orders API に実際に照会する(運用のデフォルト)、
 * {@link DisabledPaypalOrderVerifier} — 検証をスキップする。認証情報なしで動くローカル開発用で、
 * 起動のたびに警告を出す。<b>運用で有効にしてはならない。</b>
 */
public interface PaypalOrderVerifier {

    /**
     * @param paypalOrderId 브라우저가 보고한 PayPal 주문 ID (신뢰하지 않는 값)
     *                      [JA] ブラウザが報告した PayPal 注文 ID (信頼しない値)
     * @return PayPal 이 확인해 준 결제 내역
     *         [JA] PayPal が確認した決済内容
     * @throws PaypalVerificationException 결제가 확인되지 않았을 때. <b>이 경우 주문을
     *         결제 완료로 처리해서는 안 된다.</b>
     *                                     [JA] 決済が確認されなかった時。<b>この場合、注文を
     *                                          決済完了として扱ってはならない。</b>
     */
    VerifiedPaypalOrder verify(String paypalOrderId);

    /**
     * 캡처된 결제를 환불한다 — 금액을 명시하므로 전액이든 일부든 같은 호출이다.
     *
     * <p>PayPal 은 <b>주문</b> id 가 아니라 <b>캡처</b> id 로 환불한다. 구현이 주문을 조회해
     * 캡처 id 를 찾아간다 — 호출자는 저장해 둔 주문 id 만 안다.
     *
     * <p>[JA] キャプチャされた決済を返金する — 金額を明示するので、全額でも一部でも同じ呼び出しである。
     * PayPal は<b>注文</b> id ではなく<b>キャプチャ</b> id で返金する。実装が注文を照会して
     * キャプチャ id を探しに行く — 呼び出し側は保存しておいた注文 id しか知らない。
     *
     *
     * @param refundKey 이 환불의 멱등 키({@code PayPal-Request-Id} 에 실린다). 부분 환불은
     *                  같은 캡처에 환불이 여럿이라 캡처 id 만으로는 두 번째가 첫 번째의
     *                  응답으로 대체된다. {@code null} 이면 캡처 id 만
     *                  [JA] この返金の冪等キー({@code PayPal-Request-Id} に載る)。部分返金は
     *                       同じキャプチャに返金が複数あるので、キャプチャ id だけでは二つ目が一つ目の
     *                       応答で置き換えられる。{@code null} ならキャプチャ id のみ
     */
    RefundedPaypalOrder refund(String paypalOrderId, java.math.BigDecimal amount, String currency,
            String refundKey);

    /**
     * 웹훅의 서명을 PayPal 에 묻는다({@code POST /v1/notifications/verify-webhook-signature}, 5단계 14). 본문은 받은 그대로 —
     * 파싱했다 다시 쓰면 숫자 · 공백이 달라져 검증이 깨질 수 있다. 꺼진 모드면 거절(검증 없이 받지 않는다).
     * [JA] ウェブフックの署名を PayPal に問う。本文は受け取ったまま。無効モードなら拒否。
     */
    default boolean verifyWebhookSignature(String webhookId, java.util.Map<String, String> headers, String rawEvent) {
        throw new PaypalVerificationException("PayPal 검증이 꺼져 있어 웹훅을 받지 않습니다.");
    }

    /**
     * 캡처 id → 그 캡처의 PayPal 주문 id({@code GET /v2/payments/captures/{id}} 의 supplementary_data.related_ids.order_id).
     * 웹훅 본문을 믿지 않고 PayPal 이 답한 값으로 우리 주문을 찾는다. [JA] キャプチャ id → PayPal の注文 id(PayPal の答えで探す)。
     */
    default String paypalOrderIdOfCapture(String captureId) {
        throw new PaypalVerificationException("PayPal 검증이 꺼져 있어 캡처를 조회하지 않습니다.");
    }
}
