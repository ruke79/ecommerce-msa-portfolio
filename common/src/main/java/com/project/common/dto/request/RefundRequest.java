package com.project.common.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 환불 요청 본문.
 *
 * <p>여기 있는 것은 <b>어느 주문</b>과 <b>왜</b> 뿐이다. 금액·결제 참조·요청자는 본문에
 * 없다 — 금액은 DB 의 주문 총액, 결제 참조는 주문에 저장된 것, 요청자는 게이트웨이가
 * 검증한 헤더에서 온다. 본문에 두면 그 값을 믿게 된다(docs/35 의 결함이 그 모양이었다).
 *
 * <p>[JA] 返金リクエストの本文。
 * ここにあるのは<b>どの注文</b>と<b>なぜ</b>だけである。金額 · 決済参照 · 要求者は本文にない — 金額は DB の注文総額、決済参照は注文に保存されたもの、
 * 要求者はゲートウェイが検証したヘッダーから来る。本文に置くとその値を信じることになる(docs/35 の欠陥がその形だった)。
 */
@Getter
@Setter
@NoArgsConstructor
public class RefundRequest {

    private String trackingId;

    /** 사유. 제공자 화면·영수증에 남을 수 있어 길이를 자르고 따옴표를 뺀다(서버). ／ [JA] 理由。プロバイダーの画面 · 領収書に残りうるので長さを切りクォートを除く(サーバー)。 */
    private String reason;

    /**
     * 부분 환불 — 품목과 수량. 비어 있으면 <b>아직 환불되지 않은 전부</b>다.
     * 금액은 여기 없다: 서버가 단가·할인 배분·배송비에서 정한다.
     *
     * <p>[JA] 部分返金 — 品目と数量。空なら<b>まだ返金されていない全部</b>である。金額はここにない: サーバーが単価 · 割引配分 · 送料から決める。
     */
    private java.util.List<OrderItemQtyRequest> items;
}
