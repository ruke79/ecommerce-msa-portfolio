package com.project.order_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.project.order_service.payment.fx.FrankfurterFxRateSource;
import com.project.order_service.payment.fx.FxRateSource;

import lombok.extern.slf4j.Slf4j;

/**
 * 실시간 환율 배선.
 *
 * <pre>
 *   payment.fx.live.enabled = false  (기본) 고정 환율(FX_RATE_*)만 쓴다
 *                           = true   Frankfurter 에서 받고, 고정 환율은 상한선·대비책이 된다
 * </pre>
 *
 * <p>켜도 <b>고정 환율을 지우지 않는다.</b> 실시간 값이 고정값과
 * {@code payment.fx.max-deviation-percent} 넘게 다르면 정책은 고정값을 쓰고 경고한다 —
 * API 가 엉뚱한 값을 주는 날(통화 코드 혼동, 데이터 사고) 청구액이 열 배가 되는 것을
 * 막는 마지막 줄이다. API 가 죽으면 고정값으로 넘어간다.
 *
 * <p>⚠️ order-service 파드에서 {@code api.frankfurter.app} 으로 나가는 HTTPS 가 열려 있어야
 * 한다. 막혀 있으면 기동은 되지만 매 갱신이 실패하고(경고 로그) 고정 환율만 쓰게 된다 —
 * 켰는데 값이 안 바뀌면 그 로그를 먼저 본다.
 *
 * <p>{@code PaymentCurrencyPolicy} 에 이 빈을 여기서 직접 넣지 않는다 — 그러면 정책 ↔ 이
 * 설정이 순환한다. 정책이 {@code @Autowired(required = false)} 로 받아 간다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "payment.fx.live.enabled", havingValue = "true")
public class FxConfig {

    @Value("${payment.fx.live.url:https://api.frankfurter.app}")
    private String url;

    @Value("${payment.fx.live.ttl-minutes:60}")
    private long ttlMinutes;

    @Value("${payment.fx.live.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${payment.fx.live.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Bean
    public FxRateSource fxRateSource() {
        if (ttlMinutes < 1) {
            throw new IllegalStateException(
                    "payment.fx.live.ttl-minutes (FX_LIVE_TTL_MINUTES) 는 1 이상이어야 합니다: " + ttlMinutes);
        }
        ClientHttpRequestFactory factory = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .buildRequestFactory();
        log.info("실시간 환율 활성화 (url={}, ttl={}분) — 고정 환율은 상한선·대비책으로 남는다", url, ttlMinutes);
        return new FrankfurterFxRateSource(
                RestClient.builder().baseUrl(url).requestFactory(factory).build(),
                Duration.ofMinutes(ttlMinutes));
    }
}
