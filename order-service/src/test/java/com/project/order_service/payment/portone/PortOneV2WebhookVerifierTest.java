package com.project.order_service.payment.portone;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.order_service.payment.PaymentVerificationException;

/**
 * 포트원 V2 웹훅 서명 — Standard Webhooks.
 *
 * <p>Stripe 검증기와 같은 경계 조건을 고정하되, 규격이 다른 곳을 하나 더 잡는다:
 * <b>비밀키는 {@code whsec_} 뒤 base64 를 디코딩한 바이트</b>다. 문자열을 그대로 키로
 * 쓰면 모든 서명이 불일치한다 — 그 실수를 회귀 테스트로 막는다.
 */
@DisplayName("포트원 V2 웹훅 서명")
class PortOneV2WebhookVerifierTest {

    private static final byte[] KEY = "portone-test-key-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final String PAYLOAD = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"payment-1\"}}";
    private static final String ID = "msg_abc";
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private static String sig(byte[] key, String id, long ts, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "v1," + Base64.getEncoder().encodeToString(
                    mac.doFinal((id + "." + ts + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PortOneV2WebhookVerifier verifier() {
        return new PortOneV2WebhookVerifier(SECRET);
    }

    @Test
    @DisplayName("올바른 서명은 통과한다")
    void valid() {
        long ts = NOW.getEpochSecond();
        assertThatCode(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), sig(KEY, ID, ts, PAYLOAD), NOW))
                .doesNotThrowAnyException();
    }

    /** 🔴 키는 base64 를 디코딩한 바이트다. 문자열 그대로 서명하면 불일치해야 한다. */
    @Test
    @DisplayName("🔴 whsec_ 문자열을 그대로 키로 쓴 서명은 거부한다")
    void rawSecretStringIsNotTheKey() {
        long ts = NOW.getEpochSecond();
        String wrong = sig(SECRET.getBytes(StandardCharsets.UTF_8), ID, ts, PAYLOAD);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), wrong, NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("일치하지 않습니다");
    }

    @Test
    @DisplayName("본문이 바뀌면 거부한다")
    void tamperedBody() {
        long ts = NOW.getEpochSecond();
        String s = sig(KEY, ID, ts, PAYLOAD);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD.replace("payment-1", "payment-2"), ID, String.valueOf(ts), s, NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    @Test
    @DisplayName("webhook-id 가 바뀌면 거부한다 — id 도 서명 대상이다")
    void tamperedId() {
        long ts = NOW.getEpochSecond();
        String s = sig(KEY, ID, ts, PAYLOAD);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, "msg_other", String.valueOf(ts), s, NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    @Test
    @DisplayName("🔴 오래된 시각은 거부한다 — 재전송 방어")
    void expired() {
        long ts = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();
        String s = sig(KEY, ID, ts, PAYLOAD);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), s, NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("허용 범위");
    }

    @Test
    @DisplayName("키 교체 중 서명이 둘이면 하나만 맞아도 통과한다")
    void multipleSignatures() {
        long ts = NOW.getEpochSecond();
        String header = sig("old-key".getBytes(StandardCharsets.UTF_8), ID, ts, PAYLOAD) + " " + sig(KEY, ID, ts, PAYLOAD);
        assertThatCode(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), header, NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("헤더가 하나라도 없으면 거부한다")
    void missingHeaders() {
        long ts = NOW.getEpochSecond();
        String s = sig(KEY, ID, ts, PAYLOAD);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, null, String.valueOf(ts), s, NOW))
                .isInstanceOf(PaymentVerificationException.class);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, ID, null, s, NOW))
                .isInstanceOf(PaymentVerificationException.class);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), "", NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    @Test
    @DisplayName("v1 이 아닌 서명·base64 가 아닌 값은 후보에서 빠지고, 남는 게 없으면 거부한다")
    void malformed() {
        long ts = NOW.getEpochSecond();
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, ID, String.valueOf(ts), "v0,abcd v1,%%%", NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("형식");
    }

    @Test
    @DisplayName("빈 비밀키·base64 아닌 비밀키로는 만들 수 없다")
    void badSecret() {
        assertThatThrownBy(() -> new PortOneV2WebhookVerifier("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PortOneV2WebhookVerifier("whsec_%%%")).isInstanceOf(IllegalStateException.class);
    }
}
