package com.project.cart_service.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.project.cart_service.client.CatalogServiceClient;
import com.project.common.feign.UpstreamCircuitBreakers;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * cart → catalog 서킷 브레이커(4단계 ④ 1차) — 장바구니 담기 · 조회 · 체크아웃이 부르는 catalog 호출 전부를 같은 스레드에서 감싼다
 * (UpstreamCircuitBreakers). catalog 가 아프면 30초 동안 기다리지 않고 바로 실패해, 부르는 쪽의 기존 처리(503 · 저장한 값으로 진행)를
 * 빨리 탄다 — 커넥션 · 스레드를 5초씩 쥐지 않는다.
 * [JA] cart → catalog のサーキットブレーカー — catalog が不調なら待たずにすぐ失敗し、呼ぶ側の既存処理(503 · 保存値で続行)へ速く進む。
 */
@Configuration
public class UpstreamCircuitBreakerConfig {

    /** Feign 이 만든 클라이언트(qualifier rawCatalogServiceClient, primary=false)를 감싼 것이 주입 기본값이다. [JA] Feign のクライアントを包んだものが注入の既定。 */
    @Bean
    @Primary
    CatalogServiceClient catalogServiceClientWithBreaker(
            @Qualifier("rawCatalogServiceClient") CatalogServiceClient feign,
            @Autowired(required = false) MeterRegistry meters) {
        return UpstreamCircuitBreakers.wrap(CatalogServiceClient.class, feign,
                UpstreamCircuitBreakers.breaker("cart-catalog", UpstreamCircuitBreakers.defaultConfig(), meters));
    }
}
