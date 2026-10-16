package com.project.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PreLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // RewritePath 필터가 실행되기 전의 원래 경로를 로깅
        log.info("Original Request Path: {}", exchange.getRequest().getURI().getPath());
        
        return chain.filter(exchange);
    }

    /**
     * 전역 필터 셋의 순서 — 로그(이것) → 내부 엔드포인트 차단 → 신원 헤더 제거. 간격을 둔다: 같은 order 면 상대 순서는 빈 등록
     * 순서에 맡겨져 보장되지 않는다(GlobalFilterOrderTest). 셋 다 라우트 필터(order 1 부터)보다 훨씬 앞이다.
     */
    // [JA] グローバルフィルター三つの順序 — ログ → 内部エンドポイント遮断 → 身元ヘッダー除去。同じ order だと相対順序は保証されない。
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    @Override
    public int getOrder() {
        // 가장 먼저 — 차단되는 탐색 요청(/x-service/actuator 등)도 원래 경로로 한 줄 남는다
        return ORDER;
    }
}