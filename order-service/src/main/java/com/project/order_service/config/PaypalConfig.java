package com.project.order_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.project.order_service.payment.DisabledPaypalOrderVerifier;
import com.project.order_service.payment.HttpPaypalOrderVerifier;
import com.project.order_service.payment.PaypalOrderVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * PayPal 결제 검증 배선.
 *
 * <pre>
 *   paypal.verification.mode = http      PayPal 에 실제로 조회한다 (기본값 · 운영)
 *                            = disabled  검증하지 않는다 (로컬 개발 전용, 경고를 찍는다)
 * </pre>
 *
 * <p>⚠️ <b>기본값이 http 인 것이 중요하다.</b> 자격증명을 넣지 않으면 기동이 실패한다 —
 * 그게 의도다. 결제 검증은 "설정하지 않으면 조용히 꺼지는" 종류가 되면 안 된다.
 * 검증 없이 돌리려면 {@code disabled} 를 <b>명시적으로 적어야</b> 한다.
 *
 * <p>메일·SMS 는 반대로 logging 이 기본값인데(docs/29), 안전한 방향이 서로 다르기 때문이다:
 * 메일은 실수로 <b>보내는 것</b>이 위험하고, 결제는 실수로 <b>통과시키는 것</b>이 위험하다.
 *
 * <h3>샌드박스 / 라이브</h3>
 *
 * <pre>
 *   paypal.env = sandbox   테스트 (기본값)
 *              = live      실제 결제
 * </pre>
 *
 * <p>🐛 <b>예전에는 스위치가 없었다.</b> 맞춰야 하는 값이 <b>세 군데</b>에 흩어져 있었다:
 *
 * <ol>
 *   <li>서버의 {@code PAYPAL_API_BASE_URL} (샌드박스/라이브 호스트)</li>
 *   <li>서버의 {@code PAYPAL_CLIENT_ID}/{@code SECRET}</li>
 *   <li><b>브라우저의</b> {@code REACT_APP_PAYPAL_CLIENT_ID} — 프론트 <b>빌드 시점</b>에
 *       박힌다(B-51)</li>
 * </ol>
 *
 * <p>셋이 어긋나도 <b>기동은 성공한다.</b> 증상은 결제 화면까지 간 뒤에야 나온다 —
 * 브라우저는 샌드박스에서 결제를 만들었는데 서버는 라이브에 조회하니 "그런 주문 없음"
 * 이고, 사용자에게는 "결제했는데 반영이 안 된다"로 보인다. <b>돈이 오간 뒤에 드러나는
 * 종류의 설정 실수다.</b>
 *
 * <p>그래서 {@code paypal.env} 하나로 줄이고, 호스트를 그 값에서 <b>유도한다.</b> 굳이
 * {@code api-base-url} 을 따로 주면 env 와 맞는지 확인하고 <b>어긋나면 기동을 막는다</b> —
 * 두 값이 조용히 어긋나 있는 것이 애초의 문제였다.
 */
@Slf4j
@Configuration
public class PaypalConfig {

    static final String SANDBOX_URL = "https://api-m.sandbox.paypal.com";
    static final String LIVE_URL = "https://api-m.paypal.com";

    /**
     * {@code sandbox} 또는 {@code live}. <b>이 한 값이 호스트를 정한다.</b>
     *
     * <p>기본값이 sandbox 인 이유: 설정을 깜빡한 결과가 "테스트 환경에 붙는다" 여야지
     * "실제 돈이 오간다" 여서는 안 된다. mode 의 기본값이 http 인 것과 방향이 같다 —
     * <b>실수의 대가가 작은 쪽</b>으로 떨어뜨린다.
     */
    @Value("${paypal.env:sandbox}")
    private String env;

    /**
     * 호스트를 직접 지정해야 할 때만 쓴다(사내 프록시 등). 비우면 {@link #env} 에서 유도한다.
     *
     * <p>⚠️ 채웠는데 env 와 어긋나면 <b>기동을 막는다.</b> 둘 중 어느 쪽이 진짜 의도인지
     * 코드가 알 수 없고, 잘못 고르면 결제가 조용히 어긋난다.
     */
    @Value("${paypal.api-base-url:}")
    private String apiBaseUrlOverride;

    @Value("${paypal.client-id:}")
    private String clientId;

    @Value("${paypal.client-secret:}")
    private String clientSecret;

    @Value("${paypal.verification.mode:http}")
    private String mode;

    @Value("${paypal.verification.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /**
     * 결제 확인 응답을 기다리는 시간. 길게 잡으면 사용자가 결제 후 빈 화면을 오래 본다.
     * 짧게 잡으면 정상 결제가 '확인 불가'로 거부된다 — 후자가 덜 나쁘다(돈은 PayPal 에
     * 그대로 있고, 사용자는 다시 시도할 수 있다).
     */
    @Value("${paypal.verification.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private boolean isLive() {
        return "live".equalsIgnoreCase(env);
    }

    /**
     * 실제로 쓸 PayPal 호스트.
     *
     * <p>env 가 정하고, 명시적 override 가 있으면 <b>어긋나지 않는지만</b> 확인한다.
     */
    String resolveApiBaseUrl() {
        if (!isLive() && !"sandbox".equalsIgnoreCase(env)) {
            throw new IllegalStateException(
                    "paypal.env 는 sandbox 또는 live 여야 합니다: " + env);
        }

        String derived = isLive() ? LIVE_URL : SANDBOX_URL;

        if (apiBaseUrlOverride == null || apiBaseUrlOverride.isBlank()) {
            return derived;
        }

        String override = apiBaseUrlOverride.trim();
        // 호스트가 반대 환경을 가리키면 막는다. 조용히 한쪽을 고르면 브라우저와 서버가
        // 서로 다른 PayPal 을 보게 되고, 그건 결제가 끝난 뒤에야 드러난다.
        boolean pointsAtLive = override.contains("://api-m.paypal.com");
        boolean pointsAtSandbox = override.contains("sandbox.paypal.com");
        if ((isLive() && pointsAtSandbox) || (!isLive() && pointsAtLive)) {
            throw new IllegalStateException(
                    "paypal.env=" + env + " 인데 paypal.api-base-url 이 반대 환경을 가리킵니다: "
                            + override + " — 둘 중 하나가 잘못됐습니다.");
        }
        return override;
    }

    @Bean
    public PaypalOrderVerifier paypalOrderVerifier() {
        if ("disabled".equalsIgnoreCase(mode)) {
            log.warn("═══════════════════════════════════════════════════════════════");
            log.warn(" ⚠️ PayPal 결제 검증이 꺼져 있습니다 (paypal.verification.mode=disabled).");
            log.warn("    브라우저가 '결제했다'고 말하면 서버가 그대로 믿습니다.");
            log.warn("    로컬 개발 전용입니다. 운영에서 이 줄이 보이면 즉시 고치세요.");
            log.warn("═══════════════════════════════════════════════════════════════");
            return new DisabledPaypalOrderVerifier();
        }

        if (!"http".equalsIgnoreCase(mode)) {
            throw new IllegalStateException(
                    "paypal.verification.mode 는 http 또는 disabled 여야 합니다: " + mode);
        }

        // ⚠️ 여기서 기동을 막는 것이 의도다. 자격증명이 없다고 조용히 검증을 끄면
        //    "설정을 깜빡했다"가 "결제를 검증하지 않는다"가 된다.
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "PayPal 결제 검증에 자격증명이 필요합니다. "
                            + "PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET 를 채우거나, "
                            + "검증 없이 돌리려면 PAYPAL_VERIFICATION_MODE=disabled 를 명시하세요.");
        }

        String apiBaseUrl = resolveApiBaseUrl();

        ClientHttpRequestFactory factory = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .buildRequestFactory();

        // client id 앞 6자리만 남긴다. 브라우저에 박힌 값과 같은지 <b>눈으로</b> 대조하기
        // 위해서다 — 서버는 프론트가 어떤 id 로 빌드됐는지 알 수 없다(ops/19 와 같은 방식).
        log.info("PayPal 결제 검증 활성화: env={} endpoint={} clientId={}…({}자)",
                env.toLowerCase(), apiBaseUrl,
                clientId.substring(0, Math.min(6, clientId.length())), clientId.length());
        if (isLive()) {
            log.warn("⚠️ PayPal LIVE 모드입니다 — 실제 결제가 발생합니다. "
                    + "브라우저의 REACT_APP_PAYPAL_CLIENT_ID 도 live 값인지 확인하세요.");
        }
        return new HttpPaypalOrderVerifier(
                RestClient.builder().baseUrl(apiBaseUrl).requestFactory(factory).build(),
                clientId, clientSecret);
    }
}
