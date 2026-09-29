package com.project.order_service.payment.stripe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.project.order_service.payment.PaymentVerificationException;

/**
 * Stripe 웹훅 서명 검증.
 *
 * <h3>🔴 여기가 틀리면 아무나 주문을 결제 완료로 만들 수 있다</h3>
 *
 * <p>웹훅 엔드포인트는 <b>인증 없이 공개</b>돼 있어야 한다 — Stripe 가 우리 토큰을 가질 수
 * 없기 때문이다. 그래서 "이 요청이 정말 Stripe 에서 왔는가" 를 판단하는 것은 <b>오직 이
 * 서명뿐</b>이다. 검증을 건너뛰면 그 URL 에 JSON 을 POST 하는 것만으로 결제가 조작된다.
 *
 * <p>그래서 이 한 곳만 SDK 없이 직접 구현하면서도 테스트를 촘촘히 붙였다
 * ({@code StripeWebhookVerifierTest}): 정상·다른 비밀키·본문 변조·시각 만료·미래 시각·
 * 헤더 없음·형식 깨짐·v1 여러 개.
 *
 * <h3>검증 절차 (Stripe 규격)</h3>
 *
 * <pre>
 *   Stripe-Signature: t=1700000000,v1=5257a869…,v0=…
 *
 *   1. t 와 v1 들을 뽑는다 (v0 은 옛 스킴이라 쓰지 않는다)
 *   2. signed_payload = "{t}.{원본 본문}"
 *   3. HMAC-SHA256(webhook secret, signed_payload) 을 16진수로
 *   4. v1 중 하나와 **상수 시간 비교**
 *   5. |지금 - t| 가 허용 범위 안인가 — 재전송(replay) 방어
 * </pre>
 *
 * <p>⚠️ <b>본문은 받은 그대로여야 한다.</b> JSON 으로 파싱했다가 다시 직렬화하면 공백·키
 * 순서가 달라져 서명이 깨진다. 컨트롤러가 {@code @RequestBody String} 으로 받는 이유다.
 *
 * <p>[JA] Stripe Webhook の署名検証。
 *
 * <h3>🔴 ここを間違えると、誰でも注文を決済完了にできてしまう</h3>
 *
 * <p>Webhook エンドポイントは<b>認証なしで公開</b>されていなければならない — Stripe がこちらのトークンを
 * 持つことはできないからである。したがって「このリクエストは本当に Stripe から来たのか」を判断するのは
 * <b>この署名だけ</b>である。検証を飛ばせば、その URL に JSON を POST するだけで決済が改ざんされる。
 *
 * <p>だからこの一箇所だけは SDK を使わず自前で実装しつつ、テストを綿密に付けた
 * ({@code StripeWebhookVerifierTest}): 正常・別の秘密鍵・本文改ざん・時刻切れ・未来の時刻・
 * ヘッダなし・形式崩れ・v1 が複数。
 *
 * <h3>検証手順 (Stripe 仕様)</h3>
 *
 * <pre>
 *   Stripe-Signature: t=1700000000,v1=5257a869…,v0=…
 *
 *   1. t と v1 群を取り出す (v0 は旧スキームなので使わない)
 *   2. signed_payload = "{t}.{元の本文}"
 *   3. HMAC-SHA256(webhook secret, signed_payload) を 16 進数に
 *   4. v1 のいずれかと **定数時間比較**
 *   5. |現在 - t| が許容範囲内か — 再送(replay)対策
 * </pre>
 *
 * <p>⚠️ <b>本文は受け取ったままでなければならない。</b>JSON にパースしてから再シリアライズすると、空白やキーの
 * 順序が変わって署名が壊れる。コントローラが {@code @RequestBody String} で受け取る理由である。
 */
public class StripeWebhookVerifier {

    /**
     * 허용 시각 오차. Stripe 권장값이 5분이다.
     *
     * <p>이 검사가 없으면 <b>서명이 유효한 과거 요청을 그대로 재전송</b>할 수 있다. 서명은
     * 영원히 유효하기 때문이다.
     *
     * <p>[JA] 許容する時刻のずれ。Stripe の推奨値は 5 分である。
     *
     * <p>この検査がないと、<b>署名が有効な過去のリクエストをそのまま再送</b>できてしまう。署名は
     * 永遠に有効だからである。
     */
    static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String webhookSecret;
    private final Duration tolerance;

    public StripeWebhookVerifier(String webhookSecret) {
        this(webhookSecret, DEFAULT_TOLERANCE);
    }

    StripeWebhookVerifier(String webhookSecret, Duration tolerance) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            // 빈 비밀키로 조용히 동작하면 모든 서명이 같은 값으로 계산돼 검증이 무의미해진다.
            // [JA] 空の秘密鍵で黙って動作すると、すべての署名が同じ値で計算され、検証が無意味になる。
            throw new IllegalStateException("Stripe 웹훅 비밀키가 비어 있습니다.");
        }
        this.webhookSecret = webhookSecret;
        this.tolerance = tolerance;
    }

    /**
     * @param payload         받은 <b>원본</b> 본문
     *                        [JA] 受け取った<b>元の</b>本文
     * @param signatureHeader {@code Stripe-Signature} 헤더 값
     *                        [JA] {@code Stripe-Signature} ヘッダの値
     * @throws PaymentVerificationException 서명이 맞지 않거나 너무 오래된 요청일 때
     *                                      [JA] 署名が一致しないか、古すぎるリクエストの時
     */
    public void verify(String payload, String signatureHeader) {
        verify(payload, signatureHeader, Instant.now());
    }

    /** 시각을 주입받는 것은 테스트 때문이다 — 만료 검사를 실제로 확인하려면 필요하다. */
    // [JA] 時刻を注入できるのはテストのためである — 期限切れ検査を実際に確かめるには必要だ。
    void verify(String payload, String signatureHeader, Instant now) {
        if (payload == null) {
            throw new PaymentVerificationException("Stripe 웹훅 본문이 없습니다.");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new PaymentVerificationException("Stripe 서명 헤더가 없습니다.");
        }

        long timestamp = -1;
        List<String> candidates = new ArrayList<>();

        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new PaymentVerificationException("Stripe 서명의 시각이 숫자가 아닙니다.");
                }
            } else if ("v1".equals(key)) {
                // Stripe 는 비밀키 교체 중에 v1 을 여러 개 보낸다. 하나만 맞으면 된다.
                // [JA] Stripe は秘密鍵のローテーション中に v1 を複数送る。どれか一つが一致すればよい。
                candidates.add(value);
            }
        }

        if (timestamp < 0 || candidates.isEmpty()) {
            throw new PaymentVerificationException("Stripe 서명 헤더 형식이 올바르지 않습니다.");
        }

        // 🔴 재전송 방어. 서명 자체는 영원히 유효하므로 시각을 보지 않으면 옛 요청을
        //    그대로 다시 보낼 수 있다. 미래 시각도 막는다 — 정상 요청이라면 나올 수 없다.
        // [JA] 🔴 再送対策。署名自体は永遠に有効なので、時刻を見なければ古いリクエストを
        //      そのまま再送できてしまう。未来の時刻も弾く — 正常なリクエストならあり得ない。
        long skewSeconds = Math.abs(now.getEpochSecond() - timestamp);
        if (skewSeconds > tolerance.toSeconds()) {
            throw new PaymentVerificationException(
                    "Stripe 웹훅 시각이 허용 범위를 벗어났습니다: " + skewSeconds + "초");
        }

        byte[] expected = hmac(timestamp + "." + payload);

        for (String candidate : candidates) {
            byte[] given;
            try {
                given = HexFormat.of().parseHex(candidate);
            } catch (IllegalArgumentException e) {
                continue;   // 16진수가 아닌 값은 그냥 후보에서 뺀다 ／ [JA] 16 進数でない値は単に候補から外す
            }
            // ⚠️ equals 가 아니라 MessageDigest.isEqual — **상수 시간 비교**다.
            //    바이트마다 조기 반환하면 응답 시간 차이로 서명을 한 글자씩 맞혀 갈 수 있다.
            // [JA] ⚠️ equals ではなく MessageDigest.isEqual — **定数時間比較**である。
            //      バイトごとに早期リターンすると、応答時間の差から署名を 1 文字ずつ当てていけてしまう。
            if (MessageDigest.isEqual(expected, given)) {
                return;
            }
        }

        throw new PaymentVerificationException("Stripe 웹훅 서명이 일치하지 않습니다.");
    }

    private byte[] hmac(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // HmacSHA256 은 모든 JVM 이 제공한다. 여기 오면 실행 환경이 깨진 것이다.
            // [JA] HmacSHA256 はすべての JVM が提供している。ここに来たら実行環境が壊れている。
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다.", e);
        }
    }
}
