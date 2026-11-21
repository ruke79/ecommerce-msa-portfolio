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
 *
 * <p>[JA] PayPal Orders API に直接問い合わせる検証器。
 * POST /v1/oauth2/token(client_id:secret, Basic)→ アクセストークン、GET /v2/checkout/orders/{id}(Bearer トークン)→ 状態・金額・通貨。
 * ここで <b>シークレットが初めて必要になる。</b> これまでこのリポジトリに PayPal のシークレットがなかった理由はセキュリティが良かったからではなく
 * <b>サーバーが PayPal に何も尋ねていなかった</b> からだ。
 */
@Slf4j
public class HttpPaypalOrderVerifier implements PaypalOrderVerifier {

    /** 토큰 만료 직전에 쓰다 401 을 맞지 않도록 이만큼 미리 갱신한다. */
    // [JA] トークン期限の直前に使って 401 を食らわないよう、これだけ前もって更新する。
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(1);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    /** 액세스 토큰 캐시. 주문마다 토큰을 새로 받으면 PayPal 이 레이트리밋을 건다. */
    // [JA] アクセストークンのキャッシュ。注文ごとにトークンを取り直すと PayPal がレート制限をかける。
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
            // [JA] 404 もここに来る — 存在しない注文 ID をでっち上げるとそうなる。
            //      ⚠️ paypalOrderId はログに残してよい(秘密ではない)。トークンは絶対に駄目だ。
            throw new PaypalVerificationException(
                    "PayPal 주문 조회 실패: " + paypalOrderId, e);
        }

        if (order == null) {
            throw new PaypalVerificationException("PayPal 응답이 비어 있습니다: " + paypalOrderId);
        }

        String status = order.path("status").asText("");

        // 금액은 purchase_units[0].amount 에 있다. 캡처가 끝났다면
        // payments.captures[0].amount 가 더 정확하지만(부분 캡처), 이 저장소는 전액 캡처만 쓴다.
        // [JA] 金額は purchase_units[0].amount にある。キャプチャが終わっていれば
        //      payments.captures[0].amount の方が正確だが(部分キャプチャ)、このリポジトリは全額キャプチャだけを使う。
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
     *
     * <p>[JA] 返金。
     * 2 回呼ぶ — PayPal は注文ではなく <b>キャプチャ</b> を返金するからだ: {@code GET /v2/checkout/orders/{id}} → {@code purchase_units[0].payments.captures[0].id}、
     * 次に {@code POST /v2/payments/captures/{captureId}/refund} に金額を載せて送る。
     * 金額を <b>明示</b> する。空で送ると「残り全額」になり、それでは返金額を PayPal が決めることになる。呼び出し側が DB の注文総額を渡し応答と照合する。
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
            // [JA] キャプチャがない = お金が来ていない。返金するものがない。
            throw new PaypalVerificationException(
                    "PayPal 주문에 캡처가 없어 환불할 수 없습니다: " + paypalOrderId);
        }

        // ⚠️ value 의 소수 자릿수는 **통화가 정한다** — USD "59.98", JPY "1500". JPY 에
        //    "1500.00" 을 보내면 PayPal 이 DECIMALS_NOT_SUPPORTED 로 거절한다.
        //    BigDecimal.toString 은 지수 표기가 나올 수 있어 plain 을 쓴다.
        // [JA] ⚠️ value の小数桁数は **通貨が決める** — USD "59.98"、JPY "1500"。JPY に
        //         "1500.00" を送ると PayPal が DECIMALS_NOT_SUPPORTED で拒否する。
        //         BigDecimal.toString は指数表記が出得るので plain を使う。
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
                    // [JA] 同じリクエストが二度行っても返金が二度出ないように — PayPal の冪等キー。
                    //      部分返金(V9)は同じキャプチャに返金が複数あるので返金キーを付け足す — ないと
                    //      2 回目の返金が 1 回目の応答で置き換えられ金額照合で落ちる。
                    .header("PayPal-Request-Id",
                            "refund-" + captureId + (refundKey == null || refundKey.isBlank() ? "" : "-" + refundKey))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // ⚠️ captureId 는 로그에 남겨도 된다. 토큰은 절대 안 된다.
            // [JA] ⚠️ captureId はログに残してよい。トークンは絶対に駄目だ。
            throw new PaypalVerificationException("PayPal 환불 실패: capture=" + captureId, e);
        }
        if (refund == null) {
            throw new PaypalVerificationException("PayPal 환불 응답이 비어 있습니다: " + captureId);
        }

        // 응답의 amount 는 환불된 금액이다. 없으면 요청한 금액으로 두지 않는다 —
        // "환불됐다고 믿은 금액" 과 "실제 환불액" 이 다르면 그 차이가 회계 오류다.
        // [JA] 応答の amount は返金された金額だ。なければ要求した金額で置き換えない —
        //      「返金されたと信じた金額」と「実際の返金額」が違えばその差が会計エラーだ。
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

    // ------------------------------------------------------------------ 웹훅(5단계 14)
    // [JA] Webhook

    /** 서명 검증에 쓰는 헤더 — PayPal 이 보낸 그대로. [JA] 署名検証のヘッダー。 */
    public static final java.util.List<String> WEBHOOK_HEADERS = java.util.List.of(
            "paypal-auth-algo", "paypal-cert-url", "paypal-transmission-id", "paypal-transmission-sig", "paypal-transmission-time");

    @Override
    public boolean verifyWebhookSignature(String webhookId, java.util.Map<String, String> headers, String rawEvent) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder body = new StringBuilder("{");
        try {
            for (String h : WEBHOOK_HEADERS) {
                String v = headers.get(h);
                if (v == null || v.isBlank()) {
                    return false;
                }
                body.append(om.writeValueAsString(h.substring("paypal-".length()).replace('-', '_'))).append(':')
                        .append(om.writeValueAsString(v)).append(',');
            }
            body.append("\"webhook_id\":").append(om.writeValueAsString(webhookId))
                    .append(",\"webhook_event\":").append(rawEvent).append('}');
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
        JsonNode res;
        try {
            res = http.post()
                    .uri("/v1/notifications/verify-webhook-signature")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaypalVerificationException("PayPal 웹훅 서명 확인 호출 실패", e);
        }
        return res != null && "SUCCESS".equals(res.path("verification_status").asText());
    }

    @Override
    public String paypalOrderIdOfCapture(String captureId) {
        if (captureId == null || !captureId.matches("[A-Za-z0-9-]{1,64}")) {
            return null;
        }
        JsonNode capture;
        try {
            capture = http.get()
                    .uri("/v2/payments/captures/{id}", captureId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaypalVerificationException("PayPal 캡처 조회 실패: " + captureId, e);
        }
        String id = capture == null ? "" : capture.path("supplementary_data").path("related_ids").path("order_id").asText("");
        return id.isBlank() ? null : id;
    }

    // ------------------------------------------------------------------ 토큰
    // [JA] ------------------------------------------------------------------ トークン

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
                // [JA] ⚠️ 例外メッセージに資格情報が混ざらないよう、原文をそのまま付けない。
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
