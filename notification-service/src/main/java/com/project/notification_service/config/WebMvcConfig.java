package com.project.notification_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * {@link AdminOnlyInterceptor} 를 모든 API 경로에 건다.
 *
 * <p>여기서 경로를 좁히지 않는다 — 인터셉터가 스스로 관리자 접두사만 거른다. 경로 목록을
 * 두 곳에서 관리하면 새 컨트롤러가 생길 때 둘 다 고쳐야 하고, 잊으면 그 엔드포인트만
 * 조용히 무방비가 된다(catalog-service 의 WebMvcConfig 와 같은 이유).
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminOnlyInterceptor adminOnlyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminOnlyInterceptor)
                .addPathPatterns("/api/**")
                // actuator 는 API 가 아니고 프로브가 쓴다.
                .excludePathPatterns("/actuator/**");
    }
}
