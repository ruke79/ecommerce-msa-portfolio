package com.project.order_service.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.project.common.feign.UpstreamCircuitBreakers;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.client.CouponServiceClient;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * order → catalog · coupon 서킷 브레이커(4단계 ④ 1차) — 같은 스레드에서 감싸고 원래 예외를 그대로 던진다(UpstreamCircuitBreakers).
 *
 * <p>부르는 쪽의 처리는 그대로다: 재고 예약은 닿지 못하면 예약 없이 진행(열린 동안 바로), 예약 풀기는 경고만, 색상은 빈 채로,
 * 쿠폰 견적 · 결제 시작 잡기는 막는다(400) — 열린 동안은 5초씩 기다리지 않고 바로 그 길을 탄다. 쿠폰의 404(옛 coupon 파드)는 실패로
 * 세지 않고 {@code FeignException.NotFound} 그대로 온다.
 * [JA] order → catalog · coupon のサーキットブレーカー — 呼ぶ側の処理はそのまま。開いている間は 5 秒待たずにすぐその道を進む。
 */
@Configuration
public class UpstreamCircuitBreakerConfig {

    @Bean
    @Primary
    CatalogServiceClient catalogServiceClientWithBreaker(
            @Qualifier("rawCatalogServiceClient") CatalogServiceClient feign,
            @Autowired(required = false) MeterRegistry meters) {
        return UpstreamCircuitBreakers.wrap(CatalogServiceClient.class, feign,
                UpstreamCircuitBreakers.breaker("order-catalog", UpstreamCircuitBreakers.defaultConfig(), meters));
    }

    @Bean
    @Primary
    CouponServiceClient couponServiceClientWithBreaker(
            @Qualifier("rawCouponServiceClient") CouponServiceClient feign,
            @Autowired(required = false) MeterRegistry meters) {
        return UpstreamCircuitBreakers.wrap(CouponServiceClient.class, feign,
                UpstreamCircuitBreakers.breaker("order-coupon", UpstreamCircuitBreakers.defaultConfig(), meters));
    }
}
