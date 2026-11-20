package com.project.order_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.project.order_service.payment.stripe.StripeCheckoutGateway;
import com.project.order_service.payment.stripe.StripeWebhookVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe 배선.
 *
 * <pre>
 *   stripe.enabled = false  (기본) Stripe 를 붙이지 않는다 — 결제수단 목록에도 안 뜬다
 *                  = true   자격증명이 필요하고, 없으면 기동이 실패한다
 * </pre>
 *
 * <h3>기본값이 꺼짐인 이유 — PayPal 과 반대다</h3>
 *
 * <p>PayPal 검증은 기본이 켜짐(`mode=http`)이다. 이미 쓰고 있는 결제수단이라 <b>설정을
 * 깜빡한 결과가 "검증 없이 통과"가 되면 안 되기</b> 때문이다.
 *
 * <p>Stripe 는 <b>새로 추가되는 수단</b>이다. 설정하지 않은 환경에서 기동이 실패하면
 * 기존 배포가 전부 멈춘다. 그래서 "명시적으로 켠다" 가 맞고, <b>켠 뒤에는 자격증명이
 * 없으면 기동을 막는다</b> — 반쯤 켜진 상태가 제일 나쁘다.
 *
 * <h3>🔑 test / live 는 키가 스스로 말한다</h3>
 *
 * <p>Stripe 비밀키는 {@code sk_test_…} · {@code sk_live_…} 로 시작한다. PayPal 처럼 환경
 * 스위치를 따로 둘 필요가 없고, <b>오히려 둘 수가 없다</b> — 키와 어긋난 스위치는 거짓말이
 * 되기 때문이다. 대신 기동 로그에 어느 쪽인지 찍고, live 면 경고한다.
 *
 * <p>[JA] Stripe の配線。
 * stripe.enabled = false(既定)Stripe を付けない — 決済手段の一覧にも出ない / = true 資格情報が必要で、なければ起動が失敗する。
 * 既定値がオフの理由 — PayPal と逆だ: PayPal の検証は既定がオン(`mode=http`)だ。すでに使っている決済手段なので <b>設定を
 * 忘れた結果が「検証なしで通過」になってはならない</b> からだ。
 * Stripe は <b>新しく追加される手段</b> だ。設定していない環境で起動が失敗すると既存のデプロイがすべて止まる。そこで「明示的に有効にする」
 * が正しく、<b>有効にした後は資格情報がなければ起動を止める</b> — 半分有効な状態が最も悪い。
 * 🔑 test / live はキー自身が語る: Stripe の秘密鍵は {@code sk_test_…} · {@code sk_live_…} で始まる。PayPal のように環境スイッチを
 * 別に置く必要がなく、<b>むしろ置けない</b> — キーとずれたスイッチは嘘になるからだ。代わりに起動ログにどちらかを出し、live なら警告する。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "stripe.enabled", havingValue = "true")
public class StripeConfig {

    private static final String TEST_KEY_PREFIX = "sk_test_";
    private static final String LIVE_KEY_PREFIX = "sk_live_";

    @Value("${stripe.api-base-url:https://api.stripe.com}")
    private String apiBaseUrl;

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${stripe.read-timeout-ms:5000}")
    private int readTimeoutMs;

    /** 옛 주문(청구 통화가 없는)에 쓸 상점 통화. */
    // [JA] 古い注文(請求通貨のない)に使う店舗通貨。
    @Value("${payment.base-currency:USD}")
    private String baseCurrency;

    @Bean
    public StripeCheckoutGateway stripeCheckoutGateway() {
        // ⚠️ 켰는데 자격증명이 없으면 기동을 막는다. 조용히 넘어가면 결제수단 목록에는
        //    Stripe 가 뜨는데 누르면 500 이 된다 — 사용자가 먼저 발견하게 된다.
        // [JA] ⚠️ 有効にしたのに資格情報がなければ起動を止める。黙って通すと決済手段の一覧には
        //         Stripe が出るのに押すと 500 になる — ユーザーが先に見つけることになる。
        if (secretKey.isBlank()) {
            throw new IllegalStateException(
                    "stripe.enabled=true 인데 STRIPE_SECRET_KEY 가 비어 있습니다. "
                            + "키를 채우거나 STRIPE_ENABLED=false 로 끄세요.");
        }

        if (secretKey.startsWith(LIVE_KEY_PREFIX)) {
            log.warn("⚠️ Stripe LIVE 키입니다 — 실제 결제가 발생합니다.");
        } else if (secretKey.startsWith(TEST_KEY_PREFIX)) {
            log.info("Stripe 테스트 모드 (sk_test_…)");
        } else {
            // 형식이 다르면 붙여넣기 사고이거나 제한 키(rk_)다. 막지는 않되 알린다.
            // [JA] 形式が違えば貼り付け事故か制限キー(rk_)だ。止めはしないが知らせる。
            log.warn("Stripe 비밀키가 sk_test_/sk_live_ 로 시작하지 않습니다 — 키를 확인하세요.");
        }

        ClientHttpRequestFactory factory = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .buildRequestFactory();

        log.info("Stripe 결제 활성화 (endpoint={})", apiBaseUrl);
        return new StripeCheckoutGateway(
                RestClient.builder().baseUrl(apiBaseUrl).requestFactory(factory).build(),
                secretKey, baseCurrency);
    }

    /**
     * 웹훅 서명 검증기.
     *
     * <p>비밀키가 없으면 <b>여기서 기동을 막는다.</b> 웹훅만 조용히 꺼지면 "결제는 됐는데
     * 주문에 반영이 안 된다"가 되고, 그건 브라우저가 복귀하지 않은 경우에만 나타나서
     * 재현이 어렵다.
     *
     * <p>[JA] Webhook 署名の検証器。
     * 秘密鍵がなければ <b>ここで起動を止める。</b> Webhook だけが黙って切れると「決済はされたのに注文に反映されない」となり、
     * それはブラウザが戻ってこなかった場合にだけ現れるので再現が難しい。
     */
    @Bean
    public StripeWebhookVerifier stripeWebhookVerifier() {
        if (webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "stripe.enabled=true 인데 STRIPE_WEBHOOK_SECRET 이 비어 있습니다. "
                            + "Stripe 대시보드의 웹훅 서명 비밀(whsec_…)을 채우세요.");
        }
        return new StripeWebhookVerifier(webhookSecret);
    }
}
