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
 */
@Getter
@Setter
@NoArgsConstructor
public class RefundRequest {

    private String trackingId;

    /** 사유. 제공자 화면·영수증에 남을 수 있어 길이를 자르고 따옴표를 뺀다(서버). */
    private String reason;

    /**
     * 부분 환불 — 품목과 수량. 비어 있으면 <b>아직 환불되지 않은 전부</b>다.
     * 금액은 여기 없다: 서버가 단가·할인 배분·배송비에서 정한다.
     */
    private java.util.List<OrderItemQtyRequest> items;
}
