package com.project.common.feign;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import feign.Capability;

/**
 * 모든 Feign 클라이언트에 {@link FeignResponseBody} 를 건다 — Spring Cloud OpenFeign 은 상위 컨텍스트의 {@link Capability} 빈을
 * 클라이언트마다 더한다. Feign 이 없는 서비스(gateway · eureka · config · saga-projection · notification)에는 생기지 않는다.
 *
 * <p>[JA] すべての Feign クライアントに FeignResponseBody をかける(Feign のないサービスには作られない)。
 */
@AutoConfiguration
@ConditionalOnClass(Capability.class)
public class FeignResponseBodyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignResponseBody feignResponseBody() {
        return new FeignResponseBody();
    }
}
