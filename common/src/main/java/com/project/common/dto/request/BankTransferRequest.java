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
 */
@Getter
@Setter
@NoArgsConstructor
public class BankTransferRequest {

    private String trackingId;

    /** 입금자명 — 은행 내역과 주문을 맞추는 유일한 실마리다. 50자로 자른다(서버). */
    private String depositorName;

    /** 관리자가 남기는 은행 거래 참조(선택). 결제 참조 자리에 들어간다. */
    private String depositReference;
}
