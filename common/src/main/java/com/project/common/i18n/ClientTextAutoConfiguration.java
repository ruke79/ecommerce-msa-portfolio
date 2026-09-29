package com.project.common.i18n;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 서버 문구 번역을 모든 서블릿 서비스에 — com.project.common 을 컴포넌트 스캔하지 않는 서비스(catalog·user·coupon…)도
 * 받게 자동 구성으로 건다(docs/60 §4). 스캔하는 서비스는 이미 빈이 있으므로 두 번 걸리지 않는다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ResponseBodyAdvice.class)
public class ClientTextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClientTextResponseAdvice.class)
    public ClientTextResponseAdvice clientTextResponseAdvice() {
        return new ClientTextResponseAdvice();
    }
}
