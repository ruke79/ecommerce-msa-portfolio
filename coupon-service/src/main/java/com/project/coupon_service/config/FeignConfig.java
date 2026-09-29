package com.project.coupon_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.function.ServerRequest;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {

        return requestTemplate -> {
            ServletRequestAttributes attributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
            if(attributes != null) {

                HttpServletRequest request = attributes.getRequest();

                String accessToken = request.getHeader("Authorization");                
                if (accessToken != null) {
                    requestTemplate.header("Authorization", accessToken);
                }               
            }
            else {
                log.info("Feign token: null");          
            }
        };
    }

}
