package com.project.order_service.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.model.Order;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.portone.PortOneGateway;
import com.project.order_service.payment.portone.PortOneV2WebhookVerifier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 포트원 결제의 확정.
 *
 * <p>시작 단계가 없다 — 결제창은 <b>브라우저가</b> 연다({@code IMP.request_pay}).
 * 서버가 하는 일은 브라우저나 포트원이 알려 준 {@code imp_uid} 를 <b>포트원에 다시
 * 물어 확인</b>하고 주문에 반영하는 것뿐이다.
 *
 * <h3>🔒 포트원 V1 웹훅에는 서명이 없다 — 그래도 안전한 이유</h3>
 *
 * <p>Stripe 웹훅은 HMAC 서명으로 발신자를 증명한다. <b>포트원 V1 은 그런 것이 없다.</b>
 * 그래서 이 엔드포인트는 누구나 호출할 수 있다. 그럼에도 결제를 조작할 수 없는 것은
 * 본문을 <b>단서로만</b> 쓰기 때문이다:
 *
 * <ol>
 *   <li>본문에서 취하는 것은 {@code imp_uid} <b>하나뿐</b>이다. 금액·상태·주문 참조는
 *       <b>전부 버린다.</b></li>
 *   <li>그 {@code imp_uid} 를 <b>포트원 API 에 다시 조회</b>한다 — 결제 상태·금액·
 *       {@code merchant_uid} 는 전부 그 응답에서 온다.</li>
 *   <li>{@code merchant_uid} 는 <b>우리가 만든 trackingId</b> 다. 남의 결제를 보내도
 *       우리 주문을 가리키지 않으면 거기서 끝난다.</li>
 *   <li>금액을 DB 의 주문 총액과 대조한다({@code recordPayment}).</li>
 *   <li>같은 결제를 두 주문에 붙이는 것은 유니크 제약이 막는다.</li>
 * </ol>
 *
 * <p>즉 공격자가 할 수 있는 최대치는 <b>이미 정상적으로 존재하는 결제를 한 번 더 확인하게
 * 만드는 것</b>이고, 반영은 멱등하므로 아무 일도 일어나지 않는다. <b>본문을 그대로 믿는
 * 순간 이 논리가 전부 무너진다</b> — 그래서 위 1번이 이 클래스의 핵심이다.
 *
 * <p>V2 는 Standard Webhooks 서명이 있어({@link PortOneV2WebhookVerifier}) 발신자를
 * 증명한다. 그래도 위 다섯 규칙은 그대로 둔다 — 서명은 "포트원이 보냈다" 를 말할 뿐,
 * "이 결제가 이 주문의 것이고 금액이 맞다" 는 여전히 다시 조회해서 확인한다.
 *
 * <p>{@code portone.enabled=false} 면 이 빈도 없다 — 게이트웨이가 그 조건으로만 생기므로,
 * 조건 없이 두면 포트원을 끈 환경에서 기동이 실패한다({@code StripePaymentService} 주석).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "portone.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PortOnePaymentService {

    private final OrderCacheService cahcedOrderService;
    private final OrderDataService orderDataService;
    /** V1 이든 V2 든 — 버전은 PortOneConfig 가 골랐다. */
    private final PortOneGateway gateway;
    /** V2 에서만 있다. V1 은 서명이 없다(아래 주석). */
    private final java.util.Optional<PortOneV2WebhookVerifier> webhookVerifier;
    private final ObjectMapper objectMapper;
    private final BankTransferService bankTransferService;

    /** 브라우저 복귀의 결과 — 결제가 붙었는가, 가상계좌가 붙었는가. */
    public enum Outcome { PAID, VIRTUAL_ACCOUNT_ISSUED }

    /**
     * 복귀 확인 — 브라우저의 결제창이 닫히고 콜백이 왔다.
     *
     * <p>브라우저가 보고하는 것은 {@code imp_uid} 하나이고, 그마저 믿지 않는다. 결제가
     * 끝났으면 반영하고, <b>가상계좌가 발급된 것</b>이면 그 계좌를 주문에 붙인다(입금 대기,
     * docs/49 §11). 둘 다 아니면(승인 전·취소·실패) 거절 — 예전과 같다. 어느 쪽인지는
     * 브라우저가 아니라 <b>포트원 조회</b>가 말한다.
     */
    public Outcome confirmReturn(String trackingId, String impUid, Long requesterId) {
        java.util.Optional<com.project.order_service.payment.portone.VirtualAccount> va =
                gateway.verify(impUid).completed() ? java.util.Optional.empty() : gateway.virtualAccountOf(impUid);
        if (va.isPresent()) {
            // 🔒 이 결제가 정말 이 주문을 가리키는가 — 포트원이 말하는 주문 참조로 대조한다.
            String actual = gateway.trackingIdOf(impUid);
            if (!actual.equals(trackingId)) {
                throw new PaymentVerificationException("가상계좌가 다른 주문의 것입니다: " + impUid);
            }
            bankTransferService.registerVirtualAccount(trackingId, impUid, va.get(), requesterId);
            return Outcome.VIRTUAL_ACCOUNT_ISSUED;
        }
        cahcedOrderService.recordPayment(trackingId, PaymentProvider.PORTONE, impUid, requesterId);
        return Outcome.PAID;
    }

    /**
     * 웹훅 — 포트원이 직접 알렸다.
     *
     * <p>브라우저가 돌아오지 않아도(결제 직후 창을 닫아도) 반영된다. 가상계좌처럼
     * <b>결제가 며칠 뒤에 완료되는</b> 수단에서는 이쪽이 <b>유일한</b> 경로다 — 복귀
     * 콜백이 아예 없기 때문이다.
     */
    public void handleWebhook(String payload, String webhookId, String webhookTimestamp,
            String webhookSignature) {
        // V2 면 서명이 본문보다 먼저다 — 틀리면 본문을 읽지도 않는다.
        webhookVerifier.ifPresent(v -> v.verify(payload, webhookId, webhookTimestamp, webhookSignature));

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new PaymentVerificationException("포트원 웹훅 본문을 읽지 못했습니다.", e);
        }

        // 🔒 본문에서 가져오는 것은 결제 참조 하나뿐이다. 나머지는 전부 포트원에 다시 물어본다.
        //    결제 완료·가상계좌 발급이 아닌 알림(취소·실패)은 여기서 끝난다 — 200 으로.
        java.util.Optional<String> ref = gateway.paidPaymentRefOf(event)
                .or(() -> gateway.issuedPaymentRefOf(event));
        if (ref.isEmpty()) {
            log.debug("포트원 웹훅: 처리하지 않는 알림 type={}", event.path("type").asText(""));
            return;
        }
        String impUid = ref.get();

        String trackingId = gateway.trackingIdOf(impUid);
        Order order = orderDataService.getOrderByTrackingId(trackingId);
        if (order == null) {
            // 우리 주문이 아니다 — 남의 결제를 보낸 경우 여기서 끝난다.
            throw new PaymentVerificationException("주문을 찾을 수 없습니다: trackingId=" + trackingId);
        }

        // 어느 알림인지는 본문이 아니라 포트원 조회가 말한다. 결제 완료면 반영, 가상계좌
        // 발급이면 계좌를 주문에 붙인다(입금 대기), 둘 다 아니면(취소·실패·승인 전) 무시.
        if (!gateway.verify(impUid).completed()) {
            java.util.Optional<com.project.order_service.payment.portone.VirtualAccount> va = gateway.virtualAccountOf(impUid);
            if (va.isPresent()) {
                log.info("포트원 가상계좌 발급 웹훅: orderId={} ref={}", order.getOrderId(), impUid);
                bankTransferService.registerVirtualAccount(trackingId, impUid, va.get(), null);
            } else {
                log.info("포트원 웹훅: 결제 완료도 가상계좌 발급도 아니다 — 무시. orderId={} ref={}", order.getOrderId(), impUid);
            }
            return;
        }

        // 웹훅에는 로그인한 요청자가 없다 — 소유권 대조의 상대는 주문의 고객 그 자신이다. 예전에는 고객 사본에서
        // 그 고객의 이메일을 거꾸로 찾아 넘기고, 받는 쪽이 다시 이메일로 id 를 찾았다(docs/48 §6-①).
        Long ownerId = order.getCustomerId();
        if (ownerId == null) {
            throw new PaymentVerificationException(
                    "주문자를 찾지 못했습니다: orderId=" + order.getOrderId());
        }

        log.info("포트원 웹훅 수신: orderId={} impUid={}", order.getOrderId(), impUid);
        // 사용자 토큰은 넘기지 않는다(docs/54 §1.7). 가상계좌 입금이면
        // 이것이 곧 "입금 확인" 이다 — 사가가 UNPAID 에서도 PAID 로 바꾼다.
        cahcedOrderService.recordPayment(trackingId, PaymentProvider.PORTONE, impUid, ownerId);
    }
}
