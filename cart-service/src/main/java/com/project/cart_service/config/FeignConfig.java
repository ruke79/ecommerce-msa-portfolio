package com.project.cart_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            // HTTP 요청 컨텍스트가 존재하는 경우에만 헤더를 복사합니다.
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String accessToken = request.getHeader("Authorization");

                if (accessToken != null && !accessToken.isEmpty()) {
                    requestTemplate.header("Authorization", accessToken);
                    log.debug("Feign token: Authorization header copied successfully.");
                } else {
                    // 토큰이 존재하지 않을 경우 경고 로그만 남깁니다.
                    log.warn("Feign token: Authorization header is missing in the incoming request.");
                }
            } else {
                  // 비동기 환경(예: Kafka 리스너)에서는 요청 컨텍스트가 없으므로
                // 헤더 복사를 건너뛰고, Feign Client 메서드가 토큰을 직접 인자로 받도록 합니다.
                log.debug("Feign token: No request context available. Skipping Authorization header.");
            }
        };
    }
}

