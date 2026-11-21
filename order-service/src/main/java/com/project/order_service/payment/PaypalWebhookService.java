package com.project.order_service.payment;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.service.OrderDataService;
import com.project.order_service.service.OrderDataService.ExternalRefund;
import com.project.order_service.service.ProcessedEventLedger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * PayPal 웹훅 — 콘솔 환불 · 차지백 · 분쟁을 주문에 반영한다(5단계 14). 예전에는 PayPal 이 당기기(pull)뿐이라 콘솔에서 환불해도 주문은
 * 결제 완료 그대로였고 배송까지 나갔다(쿠폰 · 재고도 그대로).
 *
 * <ul>
 *   <li>꺼짐: {@code paypal.webhook-id}(PAYPAL_WEBHOOK_ID)가 비면 받지 않는다 — 컨트롤러가 503.</li>
 *   <li>서명: PayPal 의 verify-webhook-signature 에 묻는다(본문은 받은 그대로). 실패면 거절(400 — PayPal 이 다시 보내지 않는다).</li>
 *   <li>{@code PAYMENT.CAPTURE.REFUNDED · REVERSED}: 본문의 캡처 id 로 PayPal 에 다시 물어 주문을 찾고(본문을 믿지 않는다),
 *       {@link OrderDataService#recordExternalRefund} — 같은 환불 id 면 건너뛴다(우리 API 환불도 이 웹훅이 온다).
 *       전액이면 REFUNDED · 배송 취소, 일부면 금액만 · 경보(품목은 사람이).</li>
 *   <li>{@code CUSTOMER.DISPUTE.*}: 세고 경보 — 배송을 멈출지는 사람이(분쟁 열은 두지 않았다). 이벤트 id 로 한 번만 센다.</li>
 *   <li>그 밖(구독 BILLING.SUBSCRIPTION.* · PAYMENT.SALE.* 등 — 미리 구독해 둔 것): 200 · 로그만. 리소스 버전은 이벤트마다
 *       PayPal 이 정한다(캡처 2.0 · 분쟁 · Sale 1.0) — event_type 으로 가른다.</li>
 * </ul>
 *
 * <p>[JA] PayPal のウェブフック — コンソール返金 · チャージバック · 紛争を注文に反映する。webhook-id が空なら受けない。署名は PayPal に
 *      問う。返金はキャプチャ id で PayPal に問い直して注文を探し、同じ返金 id なら飛ばす。紛争は数えて警報。その他は 200 · ログだけ。
 */
@Slf4j
@Service
public class PaypalWebhookService {

    static final String REFUND_METRIC = "order.payment.external_refund";
    static final String DISPUTE_METRIC = "order.payment.dispute";

    /** 받아 둔 결과. [JA] 受け取った結果。 */
    public enum Result { HANDLED, DUPLICATE, IGNORED }

    /** 서명 · 본문이 틀렸다 — 다시 보내도 같다(400). [JA] 署名 · 本文の誤り(400)。 */
    public static class Rejected extends RuntimeException {
        public Rejected(String why) {
            super(why);
        }
    }

    private final PaypalOrderVerifier paypal;
    private final OrderDataService orders;
    private final ProcessedEventLedger ledger;
    private final TransactionOperations tx;
    private final String webhookId;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired(required = false)
    MeterRegistry meters = new SimpleMeterRegistry();

    @Autowired
    public PaypalWebhookService(PaypalOrderVerifier paypal, OrderDataService orders, ProcessedEventLedger ledger,
            TransactionOperations tx, @Value("${paypal.webhook-id:}") String webhookId) {
        this.paypal = paypal;
        this.orders = orders;
        this.ledger = ledger;
        this.tx = tx;
        this.webhookId = webhookId == null ? "" : webhookId.trim();
    }

    /** 경보 계수기를 기동 때 0 으로(B-101). [JA] 警報カウンターを起動時に 0 で。 */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        meters.counter(DISPUTE_METRIC, "provider", "paypal");
        meters.counter(REFUND_METRIC, "provider", "paypal", "result", "partial");
        meters.counter(REFUND_METRIC, "provider", "paypal", "result", "unmatched");
    }

    public boolean enabled() {
        return !webhookId.isEmpty();
    }

    /** @param headers 소문자 이름의 요청 헤더 [JA] 小文字名のリクエストヘッダー */
    public Result handle(String rawEvent, Map<String, String> headers) {
        JsonNode event;
        try {
            event = json.readTree(rawEvent);
        } catch (Exception e) {
            throw new Rejected("JSON 이 아니다");
        }
        if (event == null || !event.hasNonNull("id") || !event.hasNonNull("event_type")) {
            throw new Rejected("id · event_type 이 없다");
        }
        if (!paypal.verifyWebhookSignature(webhookId, headers, rawEvent)) {
            throw new Rejected("서명 확인 실패");
        }
        String id = event.path("id").asText();
        String type = event.path("event_type").asText();
        if (type.equals("PAYMENT.CAPTURE.REFUNDED") || type.equals("PAYMENT.CAPTURE.REVERSED")) {
            return refund(type, event.path("resource"));
        }
        if (type.startsWith("CUSTOMER.DISPUTE.")) {
            return dispute(id, type, event.path("resource"));
        }
        log.info("PayPal 웹훅 — 처리하지 않는 종류라 받아만 둔다: type={} id={} version={}", type, id,
                event.path("resource_version").asText("-"));
        return Result.IGNORED;
    }

    private Result refund(String type, JsonNode refund) {
        String refundId = refund.path("id").asText(null);
        BigDecimal amount;
        try {
            amount = new BigDecimal(refund.path("amount").path("value").asText());
        } catch (NumberFormatException e) {
            throw new Rejected("환불 금액이 없다");
        }
        String currency = refund.path("amount").path("currency_code").asText(null);
        String captureId = null;
        for (JsonNode link : refund.path("links")) {
            String href = link.path("href").asText("");
            if ("up".equals(link.path("rel").asText()) && href.contains("/captures/")) {
                captureId = href.substring(href.lastIndexOf('/') + 1);
            }
        }
        String paypalOrderId = paypal.paypalOrderIdOfCapture(captureId);
        ExternalRefund outcome = orders.recordExternalRefund(PaymentProvider.PAYPAL, paypalOrderId, refundId, amount, currency,
                "paypal " + type);
        switch (outcome) {
            case RECORDED_PARTIAL -> meters.counter(REFUND_METRIC, "provider", "paypal", "result", "partial").increment();
            case UNKNOWN_ORDER, NOT_REFUNDABLE -> meters.counter(REFUND_METRIC, "provider", "paypal", "result", "unmatched").increment();
            default -> { }
        }
        log.info("PayPal 웹훅 환불: type={} refund={} capture={} → {}", type, refundId, captureId, outcome);
        return outcome == ExternalRefund.ALREADY_RECORDED ? Result.DUPLICATE : Result.HANDLED;
    }

    private Result dispute(String eventId, String type, JsonNode dispute) {
        boolean first = Boolean.TRUE.equals(tx.execute(s -> ledger.markFirst("paypal-webhook:" + eventId)));
        if (!first) {
            return Result.DUPLICATE;
        }
        meters.counter(DISPUTE_METRIC, "provider", "paypal").increment();
        String captureIds = dispute.path("disputed_transactions").findValuesAsText("seller_transaction_id").toString();
        log.error("PayPal 분쟁: type={} dispute={} status={} reason={} 캡처={} — 콘솔에서 확인하고 배송을 멈출지 정한다",
                type, dispute.path("dispute_id").asText("-"), dispute.path("status").asText("-"),
                dispute.path("reason").asText("-"), captureIds);
        return Result.HANDLED;
    }
}
