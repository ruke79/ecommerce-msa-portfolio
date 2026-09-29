package com.project.order_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.project.order_service.payment.portone.PortOneGateway;
import com.project.order_service.payment.portone.PortOnePaymentGateway;
import com.project.order_service.payment.portone.PortOneV2PaymentGateway;
import com.project.order_service.payment.portone.PortOneV2WebhookVerifier;

import lombok.extern.slf4j.Slf4j;

/**
 * 포트원 배선.
 *
 * <pre>
 *   portone.enabled = false  (기본) 붙이지 않는다 — 결제수단 목록에도 안 뜬다
 *                   = true   자격증명이 필요하고, 없으면 기동이 실패한다
 *   portone.version = v1     (기본) 아임포트 — imp_key/imp_secret · 가맹점 식별코드(imp…)
 *                   = v2     api.portone.io — API Secret · 웹훅 시크릿 · Store ID · 채널 키
 * </pre>
 *
 * <p>Stripe 와 같은 규칙이다. 새로 추가되는 수단이라 기본은 꺼짐이고, <b>켠 뒤에 키가
 * 없으면 기동을 막는다</b> — 반쯤 켜진 상태(목록에는 뜨는데 누르면 500)가 제일 나쁘다.
 *
 * <h3>V1 / V2 는 계정이 정한다</h3>
 *
 * <p>2024 년 이후 새로 만든 포트원 계정은 <b>V2 만</b> 있다(콘솔에 {@code imp…} 식별코드가
 * 없고 {@code store-…} 가 보인다). 그런 계정에 V1 설정을 넣으면 어디서도 맞지 않는다.
 * 콘솔이 어느 쪽인지 보고 {@code PORTONE_VERSION} 을 맞춘다 — <b>프론트 빌드 인자도 같이</b>
 * 바뀐다(V1 은 {@code REACT_APP_PORTONE_IMP_CODE}, V2 는 {@code REACT_APP_PORTONE_STORE_ID}
 * + {@code REACT_APP_PORTONE_CHANNEL_KEY}).
 *
 * <p>같은 제공자({@code PORTONE})에 게이트웨이 빈은 <b>하나만</b> 만든다. 둘이면
 * {@code PaymentGateways} 가 기동을 막는다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "portone.enabled", havingValue = "true")
public class PortOneConfig {

    @Value("${portone.version:v1}")
    private String version;

    // ── V1 ──
    @Value("${portone.api-base-url:https://api.iamport.kr}")
    private String apiBaseUrl;

    @Value("${portone.api-key:}")
    private String apiKey;

    @Value("${portone.api-secret:}")
    private String apiSecret;

    // ── V2 ──
    @Value("${portone.v2.api-base-url:https://api.portone.io}")
    private String v2ApiBaseUrl;

    @Value("${portone.v2.api-secret:}")
    private String v2ApiSecret;

    @Value("${portone.v2.webhook-secret:}")
    private String v2WebhookSecret;

    @Value("${portone.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${portone.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private boolean v2() {
        return "v2".equalsIgnoreCase(version);
    }

    @Bean
    public PortOneGateway portOnePaymentGateway() {
        ClientHttpRequestFactory factory = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .buildRequestFactory();

        if (v2()) {
            if (v2ApiSecret.isBlank()) {
                throw new IllegalStateException(
                        "portone.version=v2 인데 PORTONE_V2_API_SECRET 이 비어 있습니다. "
                                + "콘솔 → 결제연동 → API Keys 의 V2 API Secret 을 채우거나 PORTONE_ENABLED=false 로 끄세요.");
            }
            // 🔒 시크릿은 절대 찍지 않는다. 길이만 — "넘어오긴 했는지" 대조용이다.
            log.info("포트원 V2 결제 활성화 (endpoint={} apiSecret={}자)", v2ApiBaseUrl, v2ApiSecret.length());
            log.warn("⚠️ 포트원은 키만으로 테스트/실결제를 구분할 수 없습니다 — 채널 설정을 확인하세요.");
            return new PortOneV2PaymentGateway(
                    RestClient.builder().baseUrl(v2ApiBaseUrl).requestFactory(factory).build(),
                    v2ApiSecret);
        }

        if (!"v1".equalsIgnoreCase(version)) {
            throw new IllegalStateException("portone.version 은 v1 또는 v2 여야 합니다: " + version);
        }
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException(
                    "portone.enabled=true 인데 PORTONE_API_KEY / PORTONE_API_SECRET 이 "
                            + "비어 있습니다. 채우거나 PORTONE_ENABLED=false 로 끄세요. "
                            + "(콘솔에 imp… 식별코드가 없고 store-… 만 보이면 V2 계정입니다 — PORTONE_VERSION=v2)");
        }
        // 🔒 시크릿은 절대 찍지 않는다. 키는 앞 4자리만 — 상점 대조용이다.
        log.info("포트원 V1 결제 활성화 (endpoint={} apiKey={}…)",
                apiBaseUrl, apiKey.substring(0, Math.min(4, apiKey.length())));
        log.warn("⚠️ 포트원은 키만으로 테스트/실결제를 구분할 수 없습니다 — 상점 설정을 확인하세요.");
        return new PortOnePaymentGateway(
                RestClient.builder().baseUrl(apiBaseUrl).requestFactory(factory).build(),
                apiKey, apiSecret);
    }

    /**
     * V2 에서만 존재한다. V1 웹훅에는 서명이 없어 검증기가 있을 수 없다 — 그 경우의 안전
     * 논리는 {@code PortOnePaymentService} 주석에 있다.
     *
     * <p>켰는데 비밀이 없으면 기동을 막는다. 검증 없는 V2 웹훅은 V1 보다 나쁘다 — V1 은
     * 애초에 본문을 안 믿는 구조지만, "서명이 있는 줄 알고" 본문을 믿게 되기 쉽다.
     */
    @Bean
    @ConditionalOnProperty(name = "portone.version", havingValue = "v2")
    public PortOneV2WebhookVerifier portOneV2WebhookVerifier() {
        if (v2WebhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "portone.version=v2 인데 PORTONE_V2_WEBHOOK_SECRET 이 비어 있습니다. "
                            + "콘솔 → 결제연동 → 웹훅에서 엔드포인트를 만들면 나오는 whsec_… 을 채우세요.");
        }
        return new PortOneV2WebhookVerifier(v2WebhookSecret);
    }
}
