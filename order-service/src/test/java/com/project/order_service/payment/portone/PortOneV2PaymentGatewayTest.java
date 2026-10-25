package com.project.order_service.payment.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.VerifiedPayment;

/**
 * 포트원 V2 응답 매핑.
 *
 * <p>실제 API 로는 검증하지 못했다(docs/49 §8). 대신 <b>문서 규격의 응답 모양</b>을 여기
 * 고정한다 — 실제 응답이 다르면 이 테스트의 응답 본문과 {@code toVerified} 를 함께 고친다.
 * 그때 이 테스트가 "무엇을 기대하고 있었는지" 를 말해 준다.
 */
@DisplayName("포트원 V2 어댑터")
class PortOneV2PaymentGatewayTest {

    private static final String SECRET = "v2-api-secret";

    private MockRestServiceServer server;
    private PortOneV2PaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.portone.io");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PortOneV2PaymentGateway(builder.build(), SECRET);
    }

    private static String paidPayment(String status, int total, String currency, String customData) {
        return "{\"id\":\"payment-1\",\"status\":\"" + status + "\","
                + "\"amount\":{\"total\":" + total + ",\"paid\":" + total + ",\"cancelled\":0},"
                + "\"currency\":\"" + currency + "\","
                + "\"customData\":" + (customData == null ? "null" : "\"" + customData.replace("\\", "\\\\").replace("\"", "\\\"") + "\"") + "}";
    }

    @Test
    @DisplayName("🔒 인증은 'PortOne {secret}' 헤더다 — Bearer 도, 토큰 교환도 아니다")
    void authHeader() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "PortOne " + SECRET))
                .andRespond(withSuccess(paidPayment("PAID", 5998, "KRW", "{\"trackingId\":\"track-1\"}"),
                        MediaType.APPLICATION_JSON));

        VerifiedPayment v = gateway.verify("payment-1");

        server.verify();
        assertThat(v.provider()).isEqualTo(PaymentProvider.PORTONE);
        assertThat(v.completed()).isTrue();
        assertThat(v.amount()).isEqualByComparingTo("5998");
        assertThat(v.currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("PAID 가 아니면 미완료다 — READY(가상계좌 발급) · FAILED · CANCELLED")
    void notPaid() {
        for (String status : new String[] {"READY", "FAILED", "CANCELLED", "PARTIAL_CANCELLED"}) {
            server.reset();
            server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                    .andRespond(withSuccess(paidPayment(status, 5998, "KRW", null), MediaType.APPLICATION_JSON));
            assertThat(gateway.verify("payment-1").completed()).as(status).isFalse();
        }
    }

    @Test
    @DisplayName("주문 참조는 customData 의 trackingId 다 — 웹훅 본문이 아니라 조회 응답에서")
    void trackingIdFromCustomData() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(paidPayment("PAID", 5998, "KRW", "{\"trackingId\":\"track-9\"}"),
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.trackingIdOf("payment-1")).isEqualTo("track-9");
    }

    /**
     * 🐛 2026-10-25 운영: 포트원이 돌려준 customData 가 `"{\"trackingId\":…}"`(두 번 감싼 문자열)였다 — 화면이 문자열로 넘기고 SDK 가
     * 한 번 더 감쌌다. 예전에는 주문 참조 없음으로 거부해 결제가 성공해도 주문이 결제 전에 남았다.
     */
    @Test
    @DisplayName("🔴 두 번 감싼 customData(이미 만들어진 결제)에서도 trackingId 를 읽는다")
    void doubleEncodedCustomData() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(paidPayment("PAID", 5998, "KRW", "\"{\\\"trackingId\\\":\\\"track-9\\\"}\""),
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.trackingIdOf("payment-1")).isEqualTo("track-9");
    }

    @Test
    @DisplayName("customData 가 없거나 모양이 다르면 주문 참조 없음으로 거부한다")
    void missingCustomData() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(paidPayment("PAID", 5998, "KRW", "not json"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.trackingIdOf("payment-1"))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("주문 참조");
    }

    @Test
    @DisplayName("V2 는 실패를 HTTP 4xx 로 준다 — 404 는 조회 실패로 감싼다")
    void notFoundIs4xx() {
        server.expect(requestTo("https://api.portone.io/payments/nope"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"no such payment\"}"));

        assertThatThrownBy(() -> gateway.verify("nope"))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("조회 실패");
    }

    // ---------------------------------------------------------------- 가상계좌 (docs/49 §11)

    private static String virtualAccountPayment(String status) {
        return "{\"id\":\"payment-1\",\"status\":\"" + status + "\","
                + "\"amount\":{\"total\":80973,\"paid\":0},\"currency\":\"KRW\","
                + "\"customData\":\"{\\\"trackingId\\\":\\\"track-1\\\"}\","
                + "\"method\":{\"type\":\"PaymentMethodVirtualAccount\",\"bank\":\"KOOKMIN\","
                + "\"accountNumber\":\"123-45-6789\",\"remitteeName\":\"예시상점\","
                + "\"expiredAt\":\"2026-09-21T09:00:00+09:00\"}}";
    }

    @Test
    @DisplayName("발급된 가상계좌 — status=VIRTUAL_ACCOUNT_ISSUED 의 method 에서 계좌·기한·금액을 읽는다")
    void virtualAccountIssued() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(virtualAccountPayment("VIRTUAL_ACCOUNT_ISSUED"), MediaType.APPLICATION_JSON));

        var va = gateway.virtualAccountOf("payment-1");

        assertThat(va).isPresent();
        assertThat(va.get().bank()).isEqualTo("KOOKMIN");
        assertThat(va.get().accountNumber()).isEqualTo("123-45-6789");
        assertThat(va.get().holder()).isEqualTo("예시상점");
        assertThat(va.get().amount()).isEqualByComparingTo("80973");
        assertThat(va.get().currency()).isEqualTo("KRW");
        assertThat(va.get().dueAt()).isNotNull();
        // 검증 쪽은 "아직 결제 전" 이다 — 카드처럼 반영되면 안 된다.
        server.reset();
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(virtualAccountPayment("VIRTUAL_ACCOUNT_ISSUED"), MediaType.APPLICATION_JSON));
        assertThat(gateway.verify("payment-1").completed()).isFalse();
    }

    @Test
    @DisplayName("입금이 끝난(PAID) 가상계좌는 더 이상 '발급된 계좌' 가 아니다")
    void paidVirtualAccountIsNotIssued() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1"))
                .andRespond(withSuccess(virtualAccountPayment("PAID"), MediaType.APPLICATION_JSON));

        assertThat(gateway.virtualAccountOf("payment-1")).isEmpty();
    }

    @Test
    @DisplayName("발급 웹훅은 Transaction.VirtualAccountIssued 에서만 참조를 낸다")
    void issuedWebhookRef() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(gateway.issuedPaymentRefOf(om.readTree(
                "{\"type\":\"Transaction.VirtualAccountIssued\",\"data\":{\"paymentId\":\"payment-1\"}}")))
                .contains("payment-1");
        assertThat(gateway.issuedPaymentRefOf(om.readTree(
                "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"payment-1\"}}"))).isEmpty();
        // 결제 완료 참조는 여전히 완료 웹훅에서만 — 발급 웹훅으로 결제가 붙으면 안 된다.
        assertThat(gateway.paidPaymentRefOf(om.readTree(
                "{\"type\":\"Transaction.VirtualAccountIssued\",\"data\":{\"paymentId\":\"payment-1\"}}"))).isEmpty();
    }

    /** 결제 완료 웹훅만 참조를 내놓는다. 취소·실패·가상계좌 발급은 비어 있다. */
    @Test
    @DisplayName("웹훅에서는 Transaction.Paid 의 paymentId 만 취한다")
    void webhookRef() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertThat(gateway.paidPaymentRefOf(om.readTree(
                "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"payment-7\",\"storeId\":\"store-x\"}}")))
                .contains("payment-7");
        assertThat(gateway.paidPaymentRefOf(om.readTree(
                "{\"type\":\"Transaction.Cancelled\",\"data\":{\"paymentId\":\"payment-7\"}}")))
                .isEmpty();
        assertThat(gateway.paidPaymentRefOf(om.readTree("{\"type\":\"Transaction.Paid\",\"data\":{}}")))
                .isEmpty();
    }

    @Test
    @DisplayName("환불은 POST /payments/{id}/cancel 에 금액을 명시한다")
    void refund() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "PortOne " + SECRET))
                .andExpect(content().json("{\"amount\":5998,\"reason\":\"고객 요청\"}"))
                .andRespond(withSuccess(
                        "{\"cancellation\":{\"id\":\"cancel-1\",\"status\":\"SUCCEEDED\",\"totalAmount\":5998,\"reason\":\"고객 요청\"}}",
                        MediaType.APPLICATION_JSON));

        RefundResult r = gateway.refund("payment-1", new BigDecimal("5998"), "KRW", "고객 요청");

        server.verify();
        assertThat(r.accepted()).isTrue();
        assertThat(r.refundId()).isEqualTo("cancel-1");
        assertThat(r.amount()).isEqualByComparingTo("5998");
        assertThat(r.currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("환불 거절(4xx)은 이유를 달고 예외다 — 이미 취소된 결제 등")
    void refundRejected() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"PAYMENT_ALREADY_CANCELLED\",\"message\":\"already cancelled\"}"));

        assertThatThrownBy(() -> gateway.refund("payment-1", new BigDecimal("5998"), "KRW", ""))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("환불 요청 실패");
    }

    /** 🔒 V2 에도 멱등 헤더가 없다 — 취소 직전 잔액을 실어 같은 환불이 두 번 나가지 않게(포트원이 잔액 불일치로 거절). */
    @Test
    @DisplayName("🔴 취소 직전 잔액(currentCancellableAmount)을 함께 보낸다")
    void refundSendsCurrentCancellableAmount() {
        server.expect(requestTo("https://api.portone.io/payments/payment-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"currentCancellableAmount\":7000")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"amount\":2000")))
                .andRespond(withSuccess("{\"cancellation\":{\"id\":\"cancel-2\",\"status\":\"SUCCEEDED\",\"totalAmount\":2000}}",
                        MediaType.APPLICATION_JSON));

        RefundResult r = gateway.refund("payment-1", new BigDecimal("2000"), "KRW", "재고 부족", "key", new BigDecimal("7000"));

        server.verify();
        assertThat(r.accepted()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo("2000");
    }
}
