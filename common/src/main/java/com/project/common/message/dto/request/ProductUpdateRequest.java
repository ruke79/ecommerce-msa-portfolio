package com.project.common.message.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ProductUpdateRequest implements Serializable  {

    List<OrderProductRequest> orderProducts;
    // 사용자 토큰(autorizationToken)은 없다 — catalog 는 읽지 않았다(docs/54 §1.7).

    /**
     * 멱등성 키 (docs/11 §4 G8). 생산자(OrderSaga)가 "order-<orderId>" 형태로 설정한다 —
     * 재고 차감은 주문당 1회여야 하므로 orderId 가 자연 멱등 키다.
     * Kafka 재전달/사가 재실행 시 컨슈머(catalog)가 이 키로 중복을 스킵한다.
     * null 허용(구버전 생산자와의 JSON 하위호환) — null 이면 dedupe 없이 기존대로 처리된다.
     */
    String eventId;

    /**
     * 재고 <b>복원</b> 요청인가. 기본 {@code false} = 주문 결제 뒤의 차감(예전부터 있던 뜻).
     *
     * <p>{@code true} 면 같은 항목 목록을 반대로 적용한다 — 사이즈 수량을 되돌리고 판매량을
     * 뺀다. 발송 전 환불이면 환불 즉시, 발송된 뒤면 <b>반품이 입고된 때</b> 생산자(OrderSaga)가
     * 보낸다. 멱등 키는 {@code restock-<orderId>} — 차감({@code order-<orderId>})과 다른 키라
     * 둘 다 한 번씩만 반영된다.
     */
    boolean restock;

}
