package com.project.order_service.payment.portone;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.order_service.payment.RefundingGateway;

/**
 * 포트원 — V1(아임포트)과 V2 가 공유하는 계약.
 *
 * <p>두 버전은 <b>다른 API</b> 다: 인증(토큰 교환 vs API Secret 헤더), 결제 참조
 * ({@code imp_uid} vs 우리가 정한 {@code paymentId}), 웹훅(서명 없음 vs Standard Webhooks).
 * 그런데 {@code PortOnePaymentService} 가 하는 일은 같다 — "참조 하나를 받아 포트원에 다시
 * 물어보고 주문에 반영한다". 그래서 서비스는 이 인터페이스만 보고, 버전은
 * {@code PortOneConfig} 가 고른다. 같은 제공자({@code PORTONE})에 빈이 둘이면 레지스트리가
 * 기동을 막는다.
 *
 * <p>[JA] PortOne — V1(アイムポート)と V2 が共有する契約。
 * 二つのバージョンは<b>別の API</b> である: 認証(トークン交換 vs API Secret ヘッダ)、決済参照
 * ({@code imp_uid} vs 当方が決めた {@code paymentId})、Webhook(署名なし vs Standard Webhooks)。
 * しかし {@code PortOnePaymentService} がすることは同じである — 「参照を一つ受け取り、PortOne に再び
 * 問い合わせて注文に反映する」。そのためサービスはこのインタフェースだけを見て、バージョンは
 * {@code PortOneConfig} が選ぶ。同じプロバイダ({@code PORTONE})に Bean が二つあればレジストリが
 * 起動を止める。
 */
public interface PortOneGateway extends RefundingGateway {

    /**
     * 결제에 붙은 우리 주문 참조(trackingId). <b>포트원에 조회해서</b> 알아낸다 — 웹훅
     * 본문의 값을 믿지 않기 위해서다. V1 은 {@code merchant_uid}, V2 는 {@code customData}.
     *
     * <p>[JA] 決済に付いた当方の注文参照(trackingId)。<b>PortOne に照会して</b>知る — Webhook
     * 本文の値を信じないためである。V1 は {@code merchant_uid}、V2 は {@code customData}。
     */
    String trackingIdOf(String paymentRef);

    /**
     * 웹훅 본문에서 <b>결제 완료</b>를 뜻하는 결제 참조를 뽑는다. 다른 종류의 알림
     * (취소·가상계좌 발급·실패)이면 비어 있다 — 호출자는 200 으로 끝낸다.
     *
     * <p>🔒 본문에서 취하는 것은 이 참조 <b>하나뿐</b>이다. 금액·상태·주문 참조는 전부
     * 버리고 포트원에 다시 물어본다(docs/49 §5).
     *
     * <p>[JA] Webhook 本文から<b>決済完了</b>を意味する決済参照を取り出す。別の種類の通知
     * (取消・仮想口座発行・失敗)なら空である — 呼び出し側は 200 で終える。
     * 🔒 本文から取るのはこの参照<b>一つだけ</b>である。金額・状態・注文参照はすべて
     * 捨てて PortOne に再び問い合わせる(docs/49 §5)。
     */
    Optional<String> paidPaymentRefOf(JsonNode webhookBody);

    /**
     * 가상계좌 <b>발급</b> 알림에서 결제 참조를 꺼낸다 — 무통장입금 상태 기계의 "입금 안내"
     * 가 PG 쪽에서 만들어졌다는 신호다(docs/49 §11). 발급 알림이 아니면 비어 있다.
     * V1 은 상태를 본문으로 알 수 없어 {@link #paidPaymentRefOf} 와 같은 값이다.
     *
     * <p>[JA] 仮想口座<b>発行</b>通知から決済参照を取り出す — 銀行振込の状態機械の「入金案内」
     * が PG 側で作られたという合図である(docs/49 §11)。発行通知でなければ空である。
     * V1 は状態を本文から知れないので {@link #paidPaymentRefOf} と同じ値である。
     */
    Optional<String> issuedPaymentRefOf(JsonNode webhookBody);

    /**
     * 이 결제가 <b>발급됐지만 아직 입금되지 않은 가상계좌</b>면 그 내용. 포트원에 다시
     * 조회해서 읽는다 — 본문을 믿지 않는 것은 결제 확인과 같다. 카드 결제·이미 입금된
     * 가상계좌·취소된 것은 비어 있다.
     *
     * <p>[JA] この決済が<b>発行済みだがまだ入金されていない仮想口座</b>ならその内容。PortOne に再び
     * 照会して読む — 本文を信じないのは決済確認と同じである。カード決済・入金済みの
     * 仮想口座・取消されたものは空である。
     */
    Optional<VirtualAccount> virtualAccountOf(String paymentRef);
}
