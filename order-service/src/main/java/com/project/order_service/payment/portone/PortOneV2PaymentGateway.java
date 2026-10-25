package com.project.order_service.payment.portone;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.VerifiedPayment;

import lombok.extern.slf4j.Slf4j;

/**
 * 포트원 <b>V2</b> 어댑터 — {@code api.portone.io}.
 *
 * <h3>V1 과 다른 점</h3>
 *
 * <ul>
 *   <li><b>인증</b>: 토큰 교환이 없다. 매 요청에 {@code Authorization: PortOne {API_SECRET}}</li>
 *   <li><b>결제 참조</b>: 포트원이 발급하는 {@code imp_uid} 가 아니라 <b>우리가 정하는
 *       {@code paymentId}</b> 다. 브라우저가 결제창을 열 때 정하고, 조회·취소·웹훅이 전부
 *       그 값으로 온다</li>
 *   <li><b>주문 참조</b>: {@code merchant_uid} 가 없다. 대신 결제 요청에 실은
 *       {@code customData}(문자열)를 포트원이 보관하고 조회 응답에 돌려준다 — 거기에
 *       {@code trackingId} 를 넣는다</li>
 *   <li><b>실패 응답</b>: V1 은 HTTP 200 에 {@code code != 0} 이었지만 V2 는 <b>HTTP 4xx</b>
 *       + {@code {type, message}} 다. RestClient 가 예외로 바꿔 주므로 catch 에서 잡는다</li>
 *   <li><b>웹훅</b>: Standard Webhooks 서명이 있다({@link PortOneV2WebhookVerifier})</li>
 * </ul>
 *
 * <p><b>실제 응답과 어긋나면 {@link #toVerified} 하나만 고치면 된다</b> — V1 과 같은 이유로
 * 매핑을 한 곳에 모았다. docs/49 §8 의 "실제 API 로 검증하지 못했다" 는 여기도 해당한다.
 */
@Slf4j
public class PortOneV2PaymentGateway implements PortOneGateway {

    /** 결제 완료 상태. 나머지(READY · FAILED · CANCELLED · PARTIAL_CANCELLED …)는 미완료다. */
    static final String PAID = "PAID";

    /** 결제 완료 웹훅. 다른 종류(Ready · Cancelled · Failed …)는 무시한다. */
    static final String WEBHOOK_PAID = "Transaction.Paid";
    /** 가상계좌 발급 웹훅 — 무통장입금 상태 기계의 "입금 안내" 가 PG 에서 만들어졌다. */
    static final String WEBHOOK_VA_ISSUED = "Transaction.VirtualAccountIssued";
    /** V2 가 "가상계좌가 발급됐고 아직 입금 전" 이라고 말하는 값. */
    static final String VA_ISSUED = "VIRTUAL_ACCOUNT_ISSUED";

    private final RestClient http;
    private final String apiSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PortOneV2PaymentGateway(RestClient http, String apiSecret) {
        this.http = http;
        this.apiSecret = apiSecret;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PORTONE;
    }

    @Override
    public VerifiedPayment verify(String paymentId) {
        return toVerified(paymentId, fetchPayment(paymentId));
    }

    @Override
    public String trackingIdOf(String paymentId) {
        JsonNode payment = fetchPayment(paymentId);
        String trackingId = trackingIdFromCustomData(payment.path("customData").asText(""));
        if (trackingId.isBlank()) {
            throw new PaymentVerificationException("포트원 결제에 주문 참조가 없습니다: " + paymentId);
        }
        return trackingId;
    }

    @Override
    public Optional<String> paidPaymentRefOf(JsonNode body) {
        if (!WEBHOOK_PAID.equals(body.path("type").asText(""))) {
            return Optional.empty();
        }
        String paymentId = body.path("data").path("paymentId").asText("");
        return paymentId.isBlank() ? Optional.empty() : Optional.of(paymentId);
    }

    @Override
    public Optional<String> issuedPaymentRefOf(JsonNode body) {
        if (!WEBHOOK_VA_ISSUED.equals(body.path("type").asText(""))) {
            return Optional.empty();
        }
        String paymentId = body.path("data").path("paymentId").asText("");
        return paymentId.isBlank() ? Optional.empty() : Optional.of(paymentId);
    }

    /**
     * 발급된 가상계좌 — {@code status=VIRTUAL_ACCOUNT_ISSUED} 일 때만. 계좌는
     * {@code method}(type {@code PaymentMethodVirtualAccount}) 안에 있다: {@code bank}(코드) ·
     * {@code accountNumber} · {@code remitteeName} · {@code expiredAt}(RFC 3339).
     *
     * <p>실제 API 로는 검증하지 못했다(docs/49 §8). 모양이 다르면 여기와
     * {@code PortOneV2PaymentGatewayTest} 의 본문을 함께 고친다.
     */
    @Override
    public Optional<VirtualAccount> virtualAccountOf(String paymentId) {
        JsonNode payment = fetchPayment(paymentId);
        if (!VA_ISSUED.equals(payment.path("status").asText(""))) {
            return Optional.empty();
        }
        JsonNode method = payment.path("method");
        String account = method.path("accountNumber").asText("");
        if (account.isBlank()) {
            throw new PaymentVerificationException("포트원 가상계좌 응답에 계좌번호가 없습니다: " + paymentId);
        }
        java.time.LocalDateTime due = null;
        String expiredAt = method.path("expiredAt").asText("");
        if (!expiredAt.isBlank()) {
            try {
                due = java.time.OffsetDateTime.parse(expiredAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime();
            } catch (java.time.format.DateTimeParseException e) {
                log.warn("포트원 가상계좌 기한을 읽지 못했다 — 기본 기한을 쓴다: paymentId={} expiredAt={}", paymentId, expiredAt);
            }
        }
        return Optional.of(new VirtualAccount(
                method.path("bank").asText(""),
                account,
                method.path("remitteeName").asText(""),
                due,
                new BigDecimal(payment.path("amount").path("total").asText("0")),
                payment.path("currency").asText("KRW").toUpperCase()));
    }

    /**
     * 환불 — {@code POST /payments/{paymentId}/cancel}.
     *
     * <p>{@code amount} 를 명시한다(전액이지만 얼마인지는 DB 가 정한다). 응답의
     * {@code cancellation.status} 는 {@code SUCCEEDED} 또는 {@code REQUESTED}(PG 가 비동기로
     * 처리) — 둘 다 받아들여진 것이다. {@code FAILED} 만 거절이다.
     */
    @Override
    public RefundResult refund(String paymentId, BigDecimal amount, String currency, String reason,
            String refundKey) {   // 포트원 V2 취소에는 멱등 헤더가 없다 — 키는 쓰지 않는다
        return refund(paymentId, amount, currency, reason, refundKey, null);
    }

    /**
     * 포트원 V2 취소. 멱등 헤더가 없으니 {@code currentCancellableAmount}(취소 직전 잔액, 우리 기록)를 함께 보낸다 — 포트원
     * 잔액과 다르면 거절되므로, 앞선 시도가 성공했는데 우리 쪽이 실패로 끝나 다시 보낸 환불은 두 번 나가지 않는다.
     */
    @Override
    public RefundResult refund(String paymentId, BigDecimal amount, String currency, String reason,
            String refundKey, BigDecimal refundableBefore) {
        // 금액은 통화 자릿수의 plain 숫자로(KRW 80000, USD 59.98). BigDecimal 을 Jackson 에
        // 맡기면 9E+3 같은 지수 표기가 나올 수 있다. 사유는 JSON 문자열로 이스케이프한다.
        String body;
        try {
            body = "{\"amount\":"
                    + com.project.order_service.payment.PaymentCurrencyPolicy.plain(amount, currency)
                    + (refundableBefore == null ? "" : ",\"currentCancellableAmount\":"
                            + com.project.order_service.payment.PaymentCurrencyPolicy.plain(refundableBefore, currency))
                    + ",\"reason\":" + objectMapper.writeValueAsString(reason == null ? "" : reason) + "}";
        } catch (Exception e) {
            throw new IllegalStateException("환불 요청 본문을 만들지 못했습니다.", e);
        }

        JsonNode response;
        try {
            response = http.post()
                    .uri("/payments/{id}/cancel", paymentId)
                    .header("Authorization", "PortOne " + apiSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // PAYMENT_ALREADY_CANCELLED · CANCEL_AMOUNT_EXCEEDS_CANCELLABLE_AMOUNT 등이 4xx 로 온다.
            // ⚠️ paymentId 는 로그에 남겨도 된다. 시크릿은 절대 안 된다.
            throw new PaymentVerificationException("포트원 환불 요청 실패: " + paymentId + " — " + brief(e), e);
        }
        if (response == null) {
            throw new PaymentVerificationException("포트원 환불 응답이 비어 있습니다: " + paymentId);
        }

        JsonNode cancellation = response.path("cancellation");
        String status = cancellation.path("status").asText("");
        JsonNode total = cancellation.path("totalAmount");
        if (total.isMissingNode() || total.isNull()) {
            throw new PaymentVerificationException(
                    "포트원 환불 응답에 취소액이 없습니다: " + paymentId + " (status=" + status + ")");
        }

        log.info("포트원 V2 환불: paymentId={} cancellationId={} status={}",
                paymentId, cancellation.path("id").asText(""), status);
        return new RefundResult(
                PaymentProvider.PORTONE,
                cancellation.path("id").asText(paymentId),
                "SUCCEEDED".equals(status) || "REQUESTED".equals(status),
                status,
                new BigDecimal(total.asText("0")),
                // 취소 응답에는 통화가 없다. 결제 통화가 곧 환불 통화다 — 호출자가 넘긴 값을 돌려준다.
                currency == null ? "" : currency.toUpperCase());
    }

    // ------------------------------------------------------------------ 조회

    private JsonNode fetchPayment(String paymentId) {
        JsonNode body;
        try {
            body = http.get()
                    .uri("/payments/{id}", paymentId)
                    .header("Authorization", "PortOne " + apiSecret)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // PAYMENT_NOT_FOUND 는 404 로 온다 — 존재하지 않는 paymentId 를 지어내면 그렇게 된다.
            throw new PaymentVerificationException("포트원 결제 조회 실패: " + paymentId + " — " + brief(e), e);
        }
        if (body == null) {
            throw new PaymentVerificationException("포트원 응답이 비어 있습니다: " + paymentId);
        }
        return body;
    }

    /**
     * 포트원 V2 응답 → 제공자 중립 결과.
     *
     * <p>{@code amount.total} 이 결제 요청 금액, {@code amount.paid} 가 실제 승인액이다.
     * 둘이 다를 수 있는 것은 부분 취소 뒤뿐이고 그때는 status 가 PAID 가 아니다. 대조는
     * <b>total</b> 로 한다 — "얼마짜리 결제였나" 가 주문 총액과 맞아야 하는 값이다.
     */
    private VerifiedPayment toVerified(String paymentId, JsonNode payment) {
        String status = payment.path("status").asText("");
        String currency = payment.path("currency").asText("");
        JsonNode total = payment.path("amount").path("total");

        if (total.isMissingNode() || total.isNull()) {
            throw new PaymentVerificationException(
                    "포트원 응답에 금액이 없습니다: " + paymentId + " (status=" + status + ")");
        }

        return new VerifiedPayment(
                PaymentProvider.PORTONE,
                paymentId,
                PAID.equals(status),
                status,
                new BigDecimal(total.asText("0")),
                currency.toUpperCase(),
                trackingIdFromCustomData(payment.path("customData").asText("")).isBlank() ? null
                        : trackingIdFromCustomData(payment.path("customData").asText("")));
    }

    /**
     * {@code customData} 는 우리가 넣은 JSON 문자열이다. 모양이 다르면 빈 문자열.
     *
     * <p>🐛 2026-10-25 운영(포트원 CLI `payment view`): 저장된 값이 `"{\"trackingId\":…}"` — <b>두 번 감싼 문자열</b>이었다. 화면이
     * {@code JSON.stringify} 한 문자열을 넘기고 SDK 가 그것을 한 번 더 문자열로 만든다. 한 겹만 풀면 글자 노드라 trackingId 가 없어
     * 결제가 성공해도 주문 참조 없음으로 거부됐다. 화면은 이제 객체를 넘기고(SDK 가 한 번만 감싼다), 여기서는 이미 만들어진 결제를 위해
     * 글자 노드면 한 겹 더 푼다(그 이상은 보지 않는다).
     *
     * <p>[JA] customData が二重にエンコードされた文字列だった(画面が JSON.stringify した文字列を渡し、SDK がもう一度文字列化)。
     * 文字ノードならもう一段だけ解く。画面はオブジェクトを渡すよう直した。
     */
    String trackingIdFromCustomData(String customData) {
        if (customData == null || customData.isBlank()) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(customData);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return node.path("trackingId").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 예외 메시지의 앞부분만 — 4xx 본문에 {type, message} 가 실려 오고, 그게 원인을 말해 준다. */
    private static String brief(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
