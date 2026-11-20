package com.project.common.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 무통장입금 요청 본문 — 세 경로가 같이 쓴다(docs/49 §11).
 *
 * <ul>
 *   <li>입금 안내 받기(고객): {@code trackingId} + {@code depositorName}</li>
 *   <li>입금 확인(관리자): {@code trackingId} + {@code depositReference}(은행 거래 참조, 선택)</li>
 *   <li>입금 전 취소(고객·관리자): {@code trackingId}</li>
 * </ul>
 *
 * <p>금액은 없다. 입금 확인은 "얼마가 왔다" 를 받지 않는다 — 주문의 청구액이 왔다는 관리자의
 * 확인이고, 다르게 왔으면 확인하지 말고 고객에게 연락하는 것이 맞다.
 *
 * <p>[JA] 銀行振込リクエストの本文 — 三つの経路が共有する(docs/49 §11)。
 * 入金案内を受ける(顧客): {@code trackingId} + {@code depositorName}。入金確認(管理者): {@code trackingId} + {@code depositReference}(銀行取引参照、任意)。
 * 入金前の取消(顧客 · 管理者): {@code trackingId}。
 * 金額はない。入金確認は「いくら来た」を受け取らない — 注文の請求額が来たという管理者の確認であり、違う額が来たなら確認せず顧客に連絡するのが正しい。
 */
@Getter
@Setter
@NoArgsConstructor
public class BankTransferRequest {

    private String trackingId;

    /** 입금자명 — 은행 내역과 주문을 맞추는 유일한 실마리다. 50자로 자른다(서버). ／ [JA] 振込名義人 — 銀行明細と注文を突き合わせる唯一の手がかりである。50 文字で切る(サーバー)。 */
    private String depositorName;

    /** 관리자가 남기는 은행 거래 참조(선택). 결제 참조 자리에 들어간다. ／ [JA] 管理者が残す銀行取引参照(任意)。決済参照の位置に入る。 */
    private String depositReference;
}
