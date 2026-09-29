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
 */
public interface PortOneGateway extends RefundingGateway {

    /**
     * 결제에 붙은 우리 주문 참조(trackingId). <b>포트원에 조회해서</b> 알아낸다 — 웹훅
     * 본문의 값을 믿지 않기 위해서다. V1 은 {@code merchant_uid}, V2 는 {@code customData}.
     */
    String trackingIdOf(String paymentRef);

    /**
     * 웹훅 본문에서 <b>결제 완료</b>를 뜻하는 결제 참조를 뽑는다. 다른 종류의 알림
     * (취소·가상계좌 발급·실패)이면 비어 있다 — 호출자는 200 으로 끝낸다.
     *
     * <p>🔒 본문에서 취하는 것은 이 참조 <b>하나뿐</b>이다. 금액·상태·주문 참조는 전부
     * 버리고 포트원에 다시 물어본다(docs/49 §5).
     */
    Optional<String> paidPaymentRefOf(JsonNode webhookBody);

    /**
     * 가상계좌 <b>발급</b> 알림에서 결제 참조를 꺼낸다 — 무통장입금 상태 기계의 "입금 안내"
     * 가 PG 쪽에서 만들어졌다는 신호다(docs/49 §11). 발급 알림이 아니면 비어 있다.
     * V1 은 상태를 본문으로 알 수 없어 {@link #paidPaymentRefOf} 와 같은 값이다.
     */
    Optional<String> issuedPaymentRefOf(JsonNode webhookBody);

    /**
     * 이 결제가 <b>발급됐지만 아직 입금되지 않은 가상계좌</b>면 그 내용. 포트원에 다시
     * 조회해서 읽는다 — 본문을 믿지 않는 것은 결제 확인과 같다. 카드 결제·이미 입금된
     * 가상계좌·취소된 것은 비어 있다.
     */
    Optional<VirtualAccount> virtualAccountOf(String paymentRef);
}
