package com.project.order_service.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

/**
 * PayPal Orders API 에 직접 물어보는 검증기.
 *
 * <pre>
 *   POST /v1/oauth2/token            client_id:secret (Basic) → 액세스 토큰
 *   GET  /v2/checkout/orders/{id}    Bearer 토큰       → 상태·금액·통화
 * </pre>
 *
 * <p>여기서 <b>시크릿이 처음으로 필요해진다.</b> 그동안 이 저장소에 PayPal 시크릿이 없었던
 * 이유는 보안이 좋아서가 아니라 <b>서버가 PayPal 에 아무것도 묻지 않았기</b> 때문이다.
 */
@Slf4j
public class HttpPaypalOrderVerifier implements PaypalOrderVerifier {

    /** 토큰 만료 직전에 쓰다 401 을 맞지 않도록 이만큼 미리 갱신한다. */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(1);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    /** 액세스 토큰 캐시. 주문마다 토큰을 새로 받으면 PayPal 이 레이트리밋을 건다. */
    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public HttpPaypalOrderVerifier(RestClient http, String clientId, String clientSecret) {
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public VerifiedPaypalOrder verify(String paypalOrderId) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) {
            throw new PaypalVerificationException("paypalOrderId 가 비어 있습니다.");
        }

        JsonNode order;
        try {
            order = http.get()
                    .uri("/v2/checkout/orders/{id}", paypalOrderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // 404 도 여기로 온다 — 존재하지 않는 주문 ID 를 지어내면 그렇게 된다.
            // ⚠️ paypalOrderId 는 로그에 남겨도 된다(비밀이 아니다). 토큰은 절대 안 된다.
            throw new PaypalVerificationException(
                    "PayPal 주문 조회 실패: " + paypalOrderId, e);
        }

        if (order == null) {
            throw new PaypalVerificationException("PayPal 응답이 비어 있습니다: " + paypalOrderId);
        }

        String status = order.path("status").asText("");

        // 금액은 purchase_units[0].amount 에 있다. 캡처가 끝났다면
        // payments.captures[0].amount 가 더 정확하지만(부분 캡처), 이 저장소는 전액 캡처만 쓴다.
        JsonNode amountNode = order.path("purchase_units").path(0).path("amount");
        String value = amountNode.path("value").asText("");
        String currency = amountNode.path("currency_code").asText("");

        if (value.isBlank() || currency.isBlank()) {
            throw new PaypalVerificationException(
                    "PayPal 응답에 금액이 없습니다: " + paypalOrderId + " (status=" + status + ")");
        }

        return new VerifiedPaypalOrder(paypalOrderId, status, new BigDecimal(value), currency);
    }

    /**
     * 환불.
     *
     * <p>두 번 부른다 — PayPal 은 주문이 아니라 <b>캡처</b>를 환불하기 때문이다:
     * <ol>
     *   <li>{@code GET /v2/checkout/orders/{id}} → {@code purchase_units[0].payments.captures[0].id}</li>
     *   <li>{@code POST /v2/payments/captures/{captureId}/refund} 에 금액을 실어 보낸다</li>
     * </ol>
     *
     * <p>금액을 <b>명시</b>한다. 비워 보내면 "남은 전액" 이 되는데, 그러면 환불액을
     * PayPal 이 정하는 셈이다. 호출자가 DB 의 주문 총액을 넘기고 응답과 대조한다.
     */
    @Override
    public RefundedPaypalOrder refund(String paypalOrderId, BigDecimal amount, String currency,
            String refundKey) {
        if (paypalOrderId == null || paypalOrderId.isBlank()) {
            throw new PaypalVerificationException("paypalOrderId 가 비어 있습니다.");
        }

        JsonNode order;
        try {
            order = http.get()
                    .uri("/v2/checkout/orders/{id}", paypalOrderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaypalVerificationException("PayPal 주문 조회 실패: " + paypalOrderId, e);
        }
        String captureId = order == null ? ""
                : order.path("purchase_units").path(0).path("payments").path("captures").path(0)
                        .path("id").asText("");
        if (captureId.isBlank()) {
            // 캡처가 없다 = 돈이 오지 않았다. 환불할 것이 없다.
            throw new PaypalVerificationException(
                    "PayPal 주문에 캡처가 없어 환불할 수 없습니다: " + paypalOrderId);
        }

        // ⚠️ value 의 소수 자릿수는 **통화가 정한다** — USD "59.98", JPY "1500". JPY 에
        //    "1500.00" 을 보내면 PayPal 이 DECIMALS_NOT_SUPPORTED 로 거절한다.
        //    BigDecimal.toString 은 지수 표기가 나올 수 있어 plain 을 쓴다.
        String body = "{\"amount\":{\"value\":\""
                + com.project.order_service.payment.PaymentCurrencyPolicy.plain(amount, currency)
                + "\",\"currency_code\":\"" + currency + "\"}}";

        JsonNode refund;
        try {
            refund = http.post()
                    .uri("/v2/payments/captures/{id}/refund", captureId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    // 같은 요청이 두 번 가도 환불이 두 번 나가지 않게 — PayPal 의 멱등 키.
                    // 부분 환불(V9)은 같은 캡처에 환불이 여럿이라 환불 키를 덧붙인다 — 없으면
                    // 두 번째 환불이 첫 번째의 응답으로 대체돼 금액 대조에서 죽는다.
                    .header("PayPal-Request-Id",
                            "refund-" + captureId + (refundKey == null || refundKey.isBlank() ? "" : "-" + refundKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // ⚠️ captureId 는 로그에 남겨도 된다. 토큰은 절대 안 된다.
            throw new PaypalVerificationException("PayPal 환불 실패: capture=" + captureId, e);
        }
        if (refund == null) {
            throw new PaypalVerificationException("PayPal 환불 응답이 비어 있습니다: " + captureId);
        }

        // 응답의 amount 는 환불된 금액이다. 없으면 요청한 금액으로 두지 않는다 —
        // "환불됐다고 믿은 금액" 과 "실제 환불액" 이 다르면 그 차이가 회계 오류다.
        JsonNode amountNode = refund.path("amount");
        String value = amountNode.path("value").asText("");
        String refundedCurrency = amountNode.path("currency_code").asText("");
        if (value.isBlank() || refundedCurrency.isBlank()) {
            throw new PaypalVerificationException(
                    "PayPal 환불 응답에 금액이 없습니다: " + captureId
                            + " (status=" + refund.path("status").asText("") + ")");
        }

        return new RefundedPaypalOrder(
                refund.path("id").asText(""),
                refund.path("status").asText(""),
                new BigDecimal(value),
                refundedCurrency);
    }

    // ------------------------------------------------------------------ 토큰

    private String accessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
                return cachedToken;
            }
            JsonNode body;
            try {
                body = http.post()
                        .uri("/v1/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("grant_type=client_credentials")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientException e) {
                // ⚠️ 예외 메시지에 자격증명이 섞이지 않도록 원문을 그대로 붙이지 않는다.
                throw new PaypalVerificationException(
                        "PayPal 액세스 토큰 발급 실패 — client id/secret 과 환경(sandbox/live)을 확인하세요.", e);
            }
            if (body == null || !body.hasNonNull("access_token")) {
                throw new PaypalVerificationException("PayPal 액세스 토큰 응답에 access_token 이 없습니다.");
            }
            cachedToken = body.get("access_token").asText();
            long expiresIn = body.path("expires_in").asLong(0);
            cachedTokenExpiry = Instant.now()
                    .plusSeconds(Math.max(0, expiresIn))
                    .minus(TOKEN_SAFETY_MARGIN);
            log.debug("PayPal 액세스 토큰 갱신 (유효 {}초)", expiresIn);
            return cachedToken;
        }
    }

    private String basicAuth() {
        String raw = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
