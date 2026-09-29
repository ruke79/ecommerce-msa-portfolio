package com.project.catalog_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * AdminWriteInterceptor 를 모든 API 경로에 건다.
 *
 * <p>경로를 여기서 좁히지 않는 이유: 인터셉터가 스스로 "쓰기 메서드만" 거르므로,
 * 여기서 또 경로 목록을 관리하면 새 컨트롤러가 추가될 때 **두 곳**을 같이 고쳐야 하고,
 * 잊으면 그 엔드포인트만 조용히 무방비가 된다 — 정확히 이번에 겪은 형태의 구멍이다.
 * 기본을 "쓰기는 전부 관리자"로 두고, 예외(별점)는 인터셉터 안에 명시한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminWriteInterceptor adminWriteInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminWriteInterceptor)
                .addPathPatterns("/api/**")
                // actuator 는 API 가 아니고, 프로브가 GET 만 쓰지만 명시적으로 뺀다.
                .excludePathPatterns("/actuator/**");
    }
}
