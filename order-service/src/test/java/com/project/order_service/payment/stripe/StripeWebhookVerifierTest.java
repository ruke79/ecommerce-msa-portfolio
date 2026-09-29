package com.project.order_service.payment.stripe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.order_service.payment.PaymentVerificationException;

/**
 * Stripe 웹훅 서명 검증.
 *
 * <h3>🔴 왜 이 파일이 이렇게 촘촘한가</h3>
 *
 * <p>웹훅 엔드포인트는 <b>인증 없이 공개</b>돼 있다 — Stripe 가 우리 토큰을 가질 수 없기
 * 때문이다. 그래서 "이 요청이 정말 Stripe 에서 왔는가" 를 판단하는 것은 <b>오직 이
 * 서명뿐</b>이고, 여기가 틀리면 그 URL 에 JSON 을 POST 하는 것만으로 남의 주문을 결제
 * 완료로 만들 수 있다.
 *
 * <p>SDK 없이 직접 구현했으므로(의존성을 늘리지 않으려고) <b>그 대가로 경계 조건을 전부
 * 고정한다</b>: 정상 · 다른 비밀키 · 본문 변조 · 시각 만료 · 미래 시각 · 헤더 없음 ·
 * 형식 깨짐 · v1 여러 개 · 16진수가 아닌 값.
 */
@DisplayName("Stripe 웹훅 서명")
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String PAYLOAD = "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\"}";

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    /** Stripe 가 만드는 것과 같은 방식으로 헤더를 만든다. */
    private static String header(String secret, String payload, Instant timestamp) {
        long t = timestamp.getEpochSecond();
        return "t=" + t + ",v1=" + hmacHex(secret, t + "." + payload);
    }

    private static String hmacHex(String secret, String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StripeWebhookVerifier verifier() {
        return new StripeWebhookVerifier(SECRET);
    }

    @Test
    @DisplayName("올바른 서명은 통과한다")
    void aValidSignaturePasses() {
        assertThatCode(() -> verifier().verify(PAYLOAD, header(SECRET, PAYLOAD, NOW), NOW))
                .doesNotThrowAnyException();
    }

    /** 🔴 핵심. 비밀키를 모르면 서명을 만들 수 없어야 한다. */
    @Test
    @DisplayName("다른 비밀키로 만든 서명은 거부한다")
    void aSignatureFromAnotherSecretIsRejected() {
        String forged = header("whsec_attacker", PAYLOAD, NOW);

        assertThatThrownBy(() -> verifier().verify(PAYLOAD, forged, NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("일치하지");
    }

    /** 🔴 서명은 본문에 묶여 있어야 한다 — 아니면 금액만 바꿔 되보낼 수 있다. */
    @Test
    @DisplayName("본문이 한 글자라도 바뀌면 거부한다")
    void aTamperedPayloadIsRejected() {
        String signature = header(SECRET, PAYLOAD, NOW);
        String tampered = PAYLOAD.replace("evt_1", "evt_2");

        assertThatThrownBy(() -> verifier().verify(tampered, signature, NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    /**
     * 🔴 재전송(replay) 방어. 서명 자체는 <b>영원히 유효하다</b> — 시각을 보지 않으면
     * 한 번 가로챈 요청을 몇 달 뒤에도 그대로 다시 보낼 수 있다.
     */
    @Test
    @DisplayName("오래된 요청은 거부한다")
    void anOldRequestIsRejected() {
        Instant old = NOW.minus(Duration.ofMinutes(10));
        String signature = header(SECRET, PAYLOAD, old);

        assertThatThrownBy(() -> verifier().verify(PAYLOAD, signature, NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("시각");
    }

    /** 미래 시각도 막는다 — 정상 요청이라면 나올 수 없고, 막지 않으면 만료 검사를 우회한다. */
    @Test
    @DisplayName("미래 시각도 거부한다")
    void aFutureTimestampIsRejected() {
        Instant future = NOW.plus(Duration.ofMinutes(10));
        String signature = header(SECRET, PAYLOAD, future);

        assertThatThrownBy(() -> verifier().verify(PAYLOAD, signature, NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("시각");
    }

    @Test
    @DisplayName("허용 범위 안의 오차는 통과한다")
    void aSmallClockSkewPasses() {
        String signature = header(SECRET, PAYLOAD, NOW.minus(Duration.ofMinutes(4)));

        assertThatCode(() -> verifier().verify(PAYLOAD, signature, NOW))
                .doesNotThrowAnyException();
    }

    /** 비밀키 교체 중에는 Stripe 가 v1 을 여러 개 보낸다. 하나만 맞으면 된다. */
    @Test
    @DisplayName("v1 이 여러 개면 하나만 맞아도 통과한다")
    void anyMatchingSignaturePasses() {
        long t = NOW.getEpochSecond();
        String good = hmacHex(SECRET, t + "." + PAYLOAD);
        String bad = hmacHex("whsec_old", t + "." + PAYLOAD);

        assertThatCode(() -> verifier().verify(PAYLOAD, "t=" + t + ",v1=" + bad + ",v1=" + good, NOW))
                .doesNotThrowAnyException();
    }

    /** v0 은 옛 스킴이다. 그것만 있으면 통과해서는 안 된다. */
    @Test
    @DisplayName("v0 만 있으면 거부한다")
    void theLegacySchemeAloneIsRejected() {
        long t = NOW.getEpochSecond();

        assertThatThrownBy(() -> verifier().verify(
                PAYLOAD, "t=" + t + ",v0=" + hmacHex(SECRET, t + "." + PAYLOAD), NOW))
                .isInstanceOf(PaymentVerificationException.class)
                .hasMessageContaining("형식");
    }

    @Test
    @DisplayName("헤더가 없으면 거부한다")
    void aMissingHeaderIsRejected() {
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, null, NOW))
                .isInstanceOf(PaymentVerificationException.class);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, "   ", NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    @Test
    @DisplayName("형식이 깨진 헤더는 거부한다")
    void aMalformedHeaderIsRejected() {
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, "garbage", NOW))
                .isInstanceOf(PaymentVerificationException.class);
        assertThatThrownBy(() -> verifier().verify(PAYLOAD, "t=notanumber,v1=abc", NOW))
                .isInstanceOf(PaymentVerificationException.class);
        // 16진수가 아닌 v1 은 후보에서 빠지고, 남는 후보가 없으므로 불일치다.
        assertThatThrownBy(() -> verifier().verify(
                PAYLOAD, "t=" + NOW.getEpochSecond() + ",v1=zzzz", NOW))
                .isInstanceOf(PaymentVerificationException.class);
    }

    /**
     * 🔴 빈 비밀키로 조용히 동작하면 모든 서명이 같은 값으로 계산돼 검증이 무의미해진다.
     * 설정 실수가 <b>기동 시점에</b> 드러나야 한다.
     */
    @Test
    @DisplayName("비밀키가 비어 있으면 만들 수조차 없다")
    void anEmptySecretIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new StripeWebhookVerifier(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StripeWebhookVerifier(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
