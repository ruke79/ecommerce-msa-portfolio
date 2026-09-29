package com.project.order_service.payment.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;

/**
 * 포트원 V1 취소 — 부분 환불.
 *
 * <h3>🐛 회귀 대상 — 부분 환불이 "실패" 로 읽혀 여러 번 나갔다</h3>
 *
 * <p>V1 은 부분 취소 뒤에도 {@code status=paid} 다. 예전 코드는 {@code cancelled} 만 받아들여, 돈이 나간 부분 환불을 거부로
 * 읽고 예외를 던졌다 — 재고 부족 자동 환불은 Kafka 가 다시 보냈고, 멱등 헤더가 없어 보낼 때마다 또 환불됐다. 두 번째 부분
 * 환불부터는 누적 취소액을 이번 환불액으로 읽어 "금액이 다르다" 로 또 실패했다.
 *
 * <p>응답 모양은 포트원 V1 문서의 {@code POST /payments/cancel} 규격이다(실제 API 로는 검증하지 못했다 — docs/49 §8).
 */
@DisplayName("포트원 V1 — 부분 환불")
class PortOneV1RefundTest {

    private MockRestServiceServer server;
    private PortOnePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.iamport.kr");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PortOnePaymentGateway(builder.build(), "key", "secret");
    }

    private void expectToken() {
        server.expect(requestTo("https://api.iamport.kr/users/getToken"))
                .andRespond(withSuccess("{\"code\":0,\"response\":{\"access_token\":\"tok\",\"expired_at\":9999999999}}",
                        MediaType.APPLICATION_JSON));
    }

    private static String cancelled(String status, int cumulative, String history) {
        return "{\"code\":0,\"message\":null,\"response\":{\"imp_uid\":\"imp_1\",\"status\":\"" + status + "\","
                + "\"amount\":10000,\"cancel_amount\":" + cumulative + ",\"currency\":\"KRW\","
                + "\"cancel_history\":" + history + "}}";
    }

    @Test
    @DisplayName("🔴 두 번째 부분 환불 — status=paid 여도 받아들이고, 이번 환불액은 누적이 아니라 최근 취소 건이다")
    void secondPartialRefund() {
        expectToken();
        server.expect(requestTo("https://api.iamport.kr/payments/cancel"))
                .andExpect(method(HttpMethod.POST))
                // 🔒 취소 직전 잔액(우리 기록) — 포트원 잔액과 다르면 거절된다(같은 환불이 두 번 나가지 않게)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"checksum\":7000")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"amount\":2000")))
                .andRespond(withSuccess(cancelled("paid", 5000,
                        "[{\"pg_tid\":\"t1\",\"amount\":3000,\"cancelled_at\":1700000000},"
                                + "{\"pg_tid\":\"t2\",\"amount\":2000,\"cancelled_at\":1700000100}]"),
                        MediaType.APPLICATION_JSON));

        RefundResult r = gateway.refund("imp_1", new BigDecimal("2000"), "KRW", "재고 부족", "key", new BigDecimal("7000"));

        server.verify();
        assertThat(r.accepted()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo("2000");          // 누적 5000 이 아니다
        assertThat(r.refundId()).isEqualTo("imp_1:t2");                // 부분 환불마다 다른 참조
        assertThat(r.currency()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("전액 환불 — status=cancelled")
    void fullRefund() {
        expectToken();
        server.expect(requestTo("https://api.iamport.kr/payments/cancel"))
                .andRespond(withSuccess(cancelled("cancelled", 10000,
                        "[{\"pg_tid\":\"t1\",\"amount\":10000,\"cancelled_at\":1700000000}]"), MediaType.APPLICATION_JSON));

        RefundResult r = gateway.refund("imp_1", new BigDecimal("10000"), "KRW", "", "key", new BigDecimal("10000"));

        assertThat(r.accepted()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("🔴 잔액 불일치(이미 나간 환불을 다시 보냄)는 포트원이 code 로 거절한다 — 돈은 두 번 나가지 않는다")
    void checksumMismatchIsRejected() {
        expectToken();
        server.expect(requestTo("https://api.iamport.kr/payments/cancel"))
                .andRespond(withSuccess("{\"code\":1,\"message\":\"checksum 이 일치하지 않습니다\",\"response\":null}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.refund("imp_1", new BigDecimal("2000"), "KRW", "", "key", new BigDecimal("7000")))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("code=1");
    }

    @Test
    @DisplayName("잔액을 모르면(null) checksum 없이 보낸다 — 예전 호출과 같다")
    void noChecksumWhenUnknown() {
        expectToken();
        server.expect(requestTo("https://api.iamport.kr/payments/cancel"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("checksum"))))
                .andRespond(withSuccess(cancelled("cancelled", 10000, "[]"), MediaType.APPLICATION_JSON));

        RefundResult r = gateway.refund("imp_1", new BigDecimal("10000"), "KRW", "");

        assertThat(r.accepted()).isTrue();
        assertThat(r.amount()).isEqualByComparingTo("10000");         // 이력이 없으면 누적(첫 환불)
        assertThat(r.refundId()).isEqualTo("imp_1");
    }
}
