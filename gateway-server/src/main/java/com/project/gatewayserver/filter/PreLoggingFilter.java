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

    @Override
    public int getOrder() {
        // Ordered.HIGHEST_PRECEDENCE는 이 필터가 가장 먼저 실행되도록 합니다.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}