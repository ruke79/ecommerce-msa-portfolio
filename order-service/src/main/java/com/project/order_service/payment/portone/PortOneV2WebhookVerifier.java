package com.project.order_service.payment.portone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.project.order_service.payment.PaymentVerificationException;

/**
 * 포트원 V2 웹훅 서명 — <a href="https://www.standardwebhooks.com/">Standard Webhooks</a>.
 *
 * <p>V1 에는 없던 것이다. 그래서 V1 은 "본문을 단서로만 쓴다" 로 버텼는데(docs/49 §5),
 * V2 는 발신자를 <b>증명</b>할 수 있다. 서명이 틀리면 본문을 읽지도 않는다.
 *
 * <p>Stripe 와 규격이 다르다 — 헤더가 셋이고 서명이 base64 다:
 * <pre>
 *   webhook-id:        msg_…                         (재전송 시 같은 값)
 *   webhook-timestamp: 1726700000                    (unix 초)
 *   webhook-signature: v1,base64sig [v1,base64sig2]  (공백으로 구분, 키 교체 중 둘)
 *   서명 대상:          "{webhook-id}.{webhook-timestamp}.{본문}"
 *   비밀키:             "whsec_" 뒤의 base64 를 **디코딩한 바이트**
 * </pre>
 *
 * <p>마지막 줄이 흔한 실수다. {@code whsec_…} 문자열을 그대로 키로 쓰면 모든 서명이
 * 불일치한다 — Stripe 는 문자열 그대로, Standard Webhooks 는 디코딩한 바이트다.
 *
 * <p>[JA] PortOne V2 の Webhook 署名 — <a href="https://www.standardwebhooks.com/">Standard Webhooks</a>。
 *
 * <p>V1 にはなかったものである。そのため V1 は「本文を手がかりとしてのみ使う」ことで凌いでいたが(docs/49 §5)、
 * V2 は送信元を<b>証明</b>できる。署名が誤っていれば本文を読むことすらしない。
 *
 * <p>Stripe とは仕様が異なる — ヘッダが 3 つで、署名は base64 である:
 * <pre>
 *   webhook-id:        msg_…                         (再送時も同じ値)
 *   webhook-timestamp: 1726700000                    (unix 秒)
 *   webhook-signature: v1,base64sig [v1,base64sig2]  (空白区切り、鍵ローテーション中は 2 つ)
 *   署名対象:           "{webhook-id}.{webhook-timestamp}.{本文}"
 *   秘密鍵:             "whsec_" の後ろの base64 を**デコードしたバイト列**
 * </pre>
 *
 * <p>最後の行がよくある間違いである。{@code whsec_…} の文字列をそのまま鍵に使うと、すべての署名が
 * 一致しなくなる — Stripe は文字列そのまま、Standard Webhooks はデコードしたバイト列である。
 */
public class PortOneV2WebhookVerifier {

    static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SECRET_PREFIX = "whsec_";

    private final byte[] key;
    private final Duration tolerance;

    public PortOneV2WebhookVerifier(String webhookSecret) {
        this(webhookSecret, DEFAULT_TOLERANCE);
    }

    PortOneV2WebhookVerifier(String webhookSecret, Duration tolerance) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("포트원 V2 웹훅 비밀키가 비어 있습니다.");
        }
        String encoded = webhookSecret.startsWith(SECRET_PREFIX)
                ? webhookSecret.substring(SECRET_PREFIX.length()) : webhookSecret;
        try {
            this.key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("포트원 V2 웹훅 비밀키가 base64 가 아닙니다.", e);
        }
        this.tolerance = tolerance;
    }

    public void verify(String payload, String webhookId, String timestampHeader, String signatureHeader) {
        verify(payload, webhookId, timestampHeader, signatureHeader, Instant.now());
    }

    void verify(String payload, String webhookId, String timestampHeader, String signatureHeader,
            Instant now) {
        if (payload == null) {
            throw new PaymentVerificationException("포트원 웹훅 본문이 없습니다.");
        }
        if (isBlank(webhookId) || isBlank(timestampHeader) || isBlank(signatureHeader)) {
            throw new PaymentVerificationException("포트원 웹훅 서명 헤더가 없습니다.");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw new PaymentVerificationException("포트원 웹훅 시각이 숫자가 아닙니다.");
        }

        // 🔴 재전송 방어 — Stripe 와 같은 이유. 서명은 영원히 유효하다.
        // [JA] 🔴 再送対策 — Stripe と同じ理由。署名は永遠に有効である。
        long skewSeconds = Math.abs(now.getEpochSecond() - timestamp);
        if (skewSeconds > tolerance.toSeconds()) {
            throw new PaymentVerificationException(
                    "포트원 웹훅 시각이 허용 범위를 벗어났습니다: " + skewSeconds + "초");
        }

        List<byte[]> candidates = new ArrayList<>();
        for (String part : signatureHeader.trim().split("\\s+")) {
            int comma = part.indexOf(',');
            if (comma < 0 || !"v1".equals(part.substring(0, comma))) {
                continue;   // 다른 버전의 서명은 후보에서 뺀다 ／ [JA] 別バージョンの署名は候補から外す
            }
            try {
                candidates.add(Base64.getDecoder().decode(part.substring(comma + 1)));
            } catch (IllegalArgumentException e) {
                // base64 가 아닌 값은 후보에서 뺀다
                // [JA] base64 でない値は候補から外す
            }
        }
        if (candidates.isEmpty()) {
            throw new PaymentVerificationException("포트원 웹훅 서명 헤더 형식이 올바르지 않습니다.");
        }

        byte[] expected = hmac(webhookId.trim() + "." + timestamp + "." + payload);
        for (byte[] given : candidates) {
            // ⚠️ 상수 시간 비교 — Stripe 검증기와 같은 이유.
            // [JA] ⚠️ 定数時間比較 — Stripe の検証器と同じ理由。
            if (MessageDigest.isEqual(expected, given)) {
                return;
            }
        }
        throw new PaymentVerificationException("포트원 웹훅 서명이 일치하지 않습니다.");
    }

    private byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다.", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
