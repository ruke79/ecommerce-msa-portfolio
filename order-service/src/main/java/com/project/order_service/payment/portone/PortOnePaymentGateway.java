package com.project.order_service.payment.portone;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.RefundingGateway;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.VerifiedPayment;

import lombok.extern.slf4j.Slf4j;

/**
 * 포트원(구 아임포트) V1 — 국내 PG·간편결제 어그리게이터.
 *
 * <h3>왜 {@code HostedCheckoutGateway} 가 아닌가</h3>
 *
 * <p>포트원은 <b>브라우저가 결제창을 연다</b>({@code IMP.request_pay}). 서버가 세션을
 * 먼저 만드는 Stripe Checkout 과 달리, PayPal 과 같은 모양이다 — 그래서 시작 단계가
 * 없는 {@link PaymentGateway} 를 구현한다. <b>포트가 둘로 갈려 있는 것이 여기서 값을
 * 한다</b>: 제공자마다 시작 방식이 다른데 확인 규칙은 하나로 유지된다.
 *
 * <h3>🔒 브라우저가 금액을 정해도 괜찮은 이유</h3>
 *
 * <p>{@code IMP.request_pay} 에는 금액이 들어간다. 즉 <b>브라우저가 결제 금액을 정한다.</b>
 * 그래도 되는 이유는 확인 단계에서 <b>포트원이 알려 준 실제 결제액을 DB 의 주문 총액과
 * 대조</b>하기 때문이다({@code OrderDataService.recordPayment}). 사용자가 1원으로 바꿔
 * 결제하면 그 결제는 성공하지만 <b>주문에는 반영되지 않는다.</b>
 *
 * <p>⚠️ 그래서 이 대조를 빼면 즉시 무료 주문이 가능해진다. 포트를 만든 이유가 이
 * 규칙을 제공자마다 다시 쓰지 않기 위해서다.
 *
 * <h3>⚠️ 이 코드는 실제 API 로 검증하지 못했다</h3>
 *
 * <p>문서 규격대로 작성했다. 응답 필드 매핑이 한 곳({@link #toVerified})에 모여 있으니
 * 실제 응답과 다르면 거기만 고치면 된다. 기동·검증 실패 로그가 무엇을 받았는지 남긴다.
 */
@Slf4j
public class PortOnePaymentGateway implements PortOneGateway {

    /** 포트원이 "결제 완료" 라고 말하는 값. ready(가상계좌 발급)·cancelled·failed 가 나머지다. */
    static final String PAID = "paid";

    /** 토큰 만료 직전에 쓰다 401 을 맞지 않도록 미리 갱신한다(PayPal 쪽과 같은 이유). */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofMinutes(1);

    private final RestClient http;
    private final String apiKey;
    private final String apiSecret;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public PortOnePaymentGateway(RestClient http, String apiKey, String apiSecret) {
        this.http = http;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PORTONE;
    }

    @Override
    public VerifiedPayment verify(String impUid) {
        return toVerified(impUid, fetchPayment(impUid));
    }

    @Override
    public String trackingIdOf(String paymentRef) {
        return merchantUidOf(paymentRef);
    }

    /**
     * V1 웹훅은 결제 상태가 바뀔 때마다 {@code imp_uid} 만 실어 보낸다 — 어떤 상태인지는
     * 본문으로 알 수 없고 알 필요도 없다. 어차피 다시 조회한다.
     */
    @Override
    public java.util.Optional<String> paidPaymentRefOf(com.fasterxml.jackson.databind.JsonNode body) {
        String impUid = body.path("imp_uid").asText("");
        return impUid.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(impUid);
    }

    /** V1 웹훅은 상태를 말하지 않는다 — 발급 알림도 결제 완료 알림과 같은 모양이다. */
    @Override
    public java.util.Optional<String> issuedPaymentRefOf(com.fasterxml.jackson.databind.JsonNode body) {
        return paidPaymentRefOf(body);
    }

    /** V1 이 "가상계좌가 발급됐고 아직 입금 전" 이라고 말하는 값. */
    static final String READY = "ready";
    static final String PAY_METHOD_VBANK = "vbank";

    /**
     * 발급된 가상계좌 — {@code status=ready} 이고 {@code pay_method=vbank} 일 때만.
     * {@code vbank_date} 는 유닉스 초다.
     */
    @Override
    public java.util.Optional<VirtualAccount> virtualAccountOf(String impUid) {
        JsonNode payment = fetchPayment(impUid);
        if (!READY.equals(payment.path("status").asText(""))
                || !PAY_METHOD_VBANK.equals(payment.path("pay_method").asText(""))) {
            return java.util.Optional.empty();
        }
        String account = payment.path("vbank_num").asText("");
        if (account.isBlank()) {
            throw new PaymentVerificationException("포트원 가상계좌 응답에 계좌번호가 없습니다: " + impUid);
        }
        long due = payment.path("vbank_date").asLong(0);
        return java.util.Optional.of(new VirtualAccount(
                payment.path("vbank_name").asText(""),
                account,
                payment.path("vbank_holder").asText(""),
                due > 0 ? java.time.LocalDateTime.ofInstant(Instant.ofEpochSecond(due), java.time.ZoneId.systemDefault()) : null,
                new BigDecimal(payment.path("amount").asText("0")),
                payment.path("currency").asText("KRW").toUpperCase()));
    }

    /** 결제에 붙은 우리 주문 참조({@code merchant_uid}) — 웹훅이 어느 주문인지 아는 근거. */
    public String merchantUidOf(String impUid) {
        String merchantUid = fetchPayment(impUid).path("merchant_uid").asText("");
        if (merchantUid.isBlank()) {
            throw new PaymentVerificationException("포트원 결제에 주문 참조가 없습니다: " + impUid);
        }
        return merchantUid;
    }

    /**
     * 환불 — V1 {@code POST /payments/cancel}.
     *
     * <p>{@code amount} 를 명시한다(비우면 전액이지만, 전액이 얼마인지는 우리 DB 가 정한다).
     * 응답의 {@code status} 는 전액 취소면 {@code cancelled}, 그리고 {@code cancel_amount}
     * 에 누적 취소액이 온다. {@code code != 0} 이 거절이다 — 조회와 같은 규칙이다.
     *
     * <p>{@code checksum} 은 보내지 않는다. 그 값은 "취소 가능한 잔액이 이 값이어야 한다"
     * 는 동시성 가드인데, 우리는 주문당 환불 1회를 DB 상태로 막는다.
     */
    @Override
    public RefundResult refund(String impUid, BigDecimal amount, String currency, String reason,
            String refundKey) {   // 포트원 V1 에는 멱등 헤더가 없다 — 키는 쓰지 않는다
        return refund(impUid, amount, currency, reason, refundKey, null);
    }

    /**
     * 포트원 V1 취소(전액·부분).
     *
     * <h3>🐛 부분 환불이 "실패" 로 읽혀 <b>여러 번 나갔다</b></h3>
     *
     * <p>예전에는 {@code status == "cancelled"} 일 때만 받아들였다. V1 은 <b>부분 취소 뒤에도 {@code paid}</b> 다 — 돈은
     * 나갔는데 거부로 읽었고, 호출자는 예외를 던졌다. 재고 부족 자동 환불은 Kafka 가 세 번 더 다시 보냈고, V1 에는 멱등 헤더가
     * 없어 <b>보낼 때마다 실제로 또 환불됐다.</b> 두 번째 부분 환불부터는 누적 취소액({@code cancel_amount})을 이번 환불액으로
     * 읽어 "금액이 다르다" 로 또 실패했다.
     *
     * <ul>
     *   <li>{@code checksum} — 취소 직전 환불 가능 잔액(우리 기록). 포트원 잔액과 다르면 취소를 거절한다. 앞선 시도가 포트원에서는
     *       성공했는데 우리 쪽이 실패로 끝나 다시 보내면 잔액이 이미 줄어 있어 <b>두 번째가 나가지 않는다.</b></li>
     *   <li>받아들임은 {@code code == 0} — 실패는 포트원이 code 로 말한다. 상태는 전액이면 cancelled, 부분이면 paid 다.</li>
     *   <li>이번 환불액은 {@code cancel_history} 의 <b>가장 최근 건</b>이다(누적값이 아니라).</li>
     * </ul>
     */
    @Override
    public RefundResult refund(String impUid, BigDecimal amount, String currency, String reason,
            String refundKey, BigDecimal refundableBefore) {
        String body = "{\"imp_uid\":\"" + impUid.replace("\"", "")
                + "\",\"amount\":" + amount.toPlainString()
                + (refundableBefore == null ? "" : ",\"checksum\":" + refundableBefore.toPlainString())
                + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'")) + "\"}";

        JsonNode response;
        try {
            response = http.post()
                    .uri("/payments/cancel")
                    .header("Authorization", accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PaymentVerificationException("포트원 환불 요청 실패: " + impUid, e);
        }
        if (response == null) {
            throw new PaymentVerificationException("포트원 환불 응답이 비어 있습니다: " + impUid);
        }
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            // checksum 불일치(이미 취소된 환불을 다시 보냄)도 여기로 온다 — 돈은 나가지 않았다.
            throw new PaymentVerificationException(
                    "포트원이 환불을 거부했습니다: code=" + code
                            + ", message=" + response.path("message").asText(""));
        }

        JsonNode payment = response.path("response");
        String status = payment.path("status").asText("");
        JsonNode latest = latestCancel(payment.path("cancel_history"));
        BigDecimal refunded;
        String refundRef;
        if (latest != null) {
            refunded = new BigDecimal(latest.path("amount").asText("0"));
            // 부분 환불이 여럿이면 imp_uid 만으로는 구분되지 않는다 — 취소 건의 PG 거래 id(없으면 시각)를 붙인다.
            String tid = latest.path("pg_tid").asText("");
            refundRef = impUid + ":" + (tid.isBlank() ? latest.path("cancelled_at").asText("") : tid);
        } else {
            // 이력이 없는 응답 — 누적 취소액밖에 없다. 첫 환불이면 그것이 이번 환불액이다(아니면 호출자가 금액 불일치로 멈춘다).
            JsonNode cancelAmount = payment.path("cancel_amount");
            if (cancelAmount.isMissingNode() || cancelAmount.isNull()) {
                throw new PaymentVerificationException(
                        "포트원 환불 응답에 취소액이 없습니다: " + impUid + " (status=" + status + ")");
            }
            refunded = new BigDecimal(cancelAmount.asText("0"));
            refundRef = impUid;
        }

        log.info("포트원 환불: impUid={} status={} amount={}", impUid, status, refunded.toPlainString());
        return new RefundResult(
                PaymentProvider.PORTONE,
                refundRef,
                "cancelled".equals(status) || "paid".equals(status),
                status,
                refunded,
                payment.path("currency").asText("").toUpperCase());
    }

    /** 취소 이력 중 가장 최근 건(cancelled_at 이 가장 큰 것). 이력이 없으면 null. */
    private static JsonNode latestCancel(JsonNode history) {
        if (history == null || !history.isArray() || history.isEmpty()) {
            return null;
        }
        JsonNode latest = null;
        for (JsonNode h : history) {
            if (latest == null || h.path("cancelled_at").asLong(0) >= latest.path("cancelled_at").asLong(0)) {
                latest = h;
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ 조회

    private JsonNode fetchPayment(String impUid) {
        JsonNode body;
        try {
            body = http.get()
                    .uri("/payments/{impUid}", impUid)
                    // ⚠️ V1 은 Bearer 접두사 없이 토큰 자체를 넣는다.
                    .header("Authorization", accessToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // 404 도 여기로 온다 — 존재하지 않는 imp_uid 를 지어내면 그렇게 된다.
            // ⚠️ impUid 는 로그에 남겨도 된다(비밀이 아니다). 토큰·시크릿은 절대 안 된다.
            throw new PaymentVerificationException("포트원 결제 조회 실패: " + impUid, e);
        }

        if (body == null) {
            throw new PaymentVerificationException("포트원 응답이 비어 있습니다: " + impUid);
        }
        // 포트원은 HTTP 200 에 code != 0 으로 실패를 담는다. 상태 코드만 보면 놓친다.
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            throw new PaymentVerificationException(
                    "포트원이 조회를 거부했습니다: code=" + code
                            + ", message=" + body.path("message").asText(""));
        }
        return body.path("response");
    }

    /**
     * 포트원 응답 → 제공자 중립 결과.
     *
     * <p><b>실제 응답과 어긋나면 여기만 고치면 된다.</b> 매핑을 한 곳에 모아 둔 이유다.
     */
    private VerifiedPayment toVerified(String impUid, JsonNode payment) {
        String status = payment.path("status").asText("");
        String currency = payment.path("currency").asText("");
        // amount 는 최소 단위가 아니라 **실제 금액**이다(KRW 1000 = 1000).
        JsonNode amountNode = payment.path("amount");

        if (amountNode.isMissingNode() || amountNode.isNull()) {
            throw new PaymentVerificationException(
                    "포트원 응답에 금액이 없습니다: " + impUid + " (status=" + status + ")");
        }

        return new VerifiedPayment(
                PaymentProvider.PORTONE,
                impUid,
                PAID.equals(status),
                status,
                new BigDecimal(amountNode.asText("0")),
                currency.toUpperCase(),
                payment.path("merchant_uid").asText("").isBlank() ? null : payment.path("merchant_uid").asText(""));
    }

    // ------------------------------------------------------------------ 토큰

    private String accessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return token;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // 다른 스레드가 방금 갱신했을 수 있다.
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("imp_key", apiKey);
        form.add("imp_secret", apiSecret);

        JsonNode body;
        try {
            body = http.post()
                    .uri("/users/getToken")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // ⚠️ 예외 메시지에 시크릿이 실리지 않도록 원문을 붙이지 않는다.
            throw new PaymentVerificationException("포트원 인증에 실패했습니다.", e);
        }

        if (body == null || body.path("code").asInt(-1) != 0) {
            throw new PaymentVerificationException("포트원 인증에 실패했습니다 — 키를 확인하세요.");
        }

        JsonNode response = body.path("response");
        String token = response.path("access_token").asText("");
        long expiredAt = response.path("expired_at").asLong(0);
        if (token.isBlank()) {
            throw new PaymentVerificationException("포트원 토큰 응답이 비어 있습니다.");
        }

        cachedToken = token;
        cachedTokenExpiry = (expiredAt > 0)
                ? Instant.ofEpochSecond(expiredAt).minus(TOKEN_SAFETY_MARGIN)
                // expired_at 이 없으면 보수적으로 짧게 잡는다(V1 기본 만료는 30분이다).
                : Instant.now().plus(Duration.ofMinutes(20));
        return token;
    }
}
