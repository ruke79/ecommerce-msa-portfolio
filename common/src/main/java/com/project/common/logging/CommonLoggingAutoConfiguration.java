package com.project.common.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

/**
 * 로그 문맥(MDC)을 채우고 넘기는 빈 — common 을 쓰는 모든 서비스에 자동으로 붙는다 (docs/51 §3).
 *
 * <p>컴포넌트 스캔이 아니라 자동 구성인 이유: 대부분의 서비스가 {@code com.project.common} 을
 * 스캔하지 않는다(order·cart·chat 만 한다). 스캔에 기대면 서비스마다 붙기도 하고 안 붙기도 한다.
 * {@code META-INF/spring/...AutoConfiguration.imports} 에 등록했다.
 */
@AutoConfiguration(before = TaskExecutionAutoConfiguration.class)
public class CommonLoggingAutoConfiguration {

    /**
     * 스프링 부트의 기본 {@code @Async} 실행기는 {@link TaskDecorator} 빈이 하나 있으면 그것을 쓴다.
     * 서비스가 자기 데코레이터를 두었으면 물러난다.
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public TaskDecorator mdcTaskDecorator() {
        MdcTaskDecorator.warnIfNoContextPropagation();
        return new MdcTaskDecorator();
    }

    /**
     * Kafka 를 쓰는 서비스에만 — 보내는 메시지에 추적 헤더를 싣는다(docs/51 §4d).
     * 끄려면 {@code app.tracing.kafka-producer=false}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.tracing.kafka-producer", havingValue = "true", matchIfMissing = true)
    static class KafkaProducerTracing {

        /** static — 후처리기는 다른 빈보다 먼저 만들어져야 한다. */
        @Bean
        static KafkaTemplateObservationPostProcessor kafkaTemplateObservationPostProcessor() {
            return new KafkaTemplateObservationPostProcessor();
        }
    }

    /**
     * 아웃박스를 건너는 추적(docs/51 §4e). 추적 빈은 쓰는 순간에 찾는다 — 자동 구성 순서상 이 빈이
     * 먼저 만들어질 수 있다. 추적이 없는 서비스에서는 아무것도 하지 않는다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class OutboxTracing {

        @Bean
        @ConditionalOnMissingBean
        com.project.common.outbox.OutboxTraceContext outboxTraceContext(
                org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
            return new com.project.common.outbox.OutboxTraceContext(tracer::getIfAvailable, propagator::getIfAvailable);
        }
    }

    /** 서블릿 서비스에만. 게이트웨이(리액티브)에는 필터 체인이 달라 붙지 않는다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class ServletMdc {

        /**
         * 보안 필터(-100)보다 앞이다 — 인증 실패 로그에도 userId 가 붙게. 추적 필터
         * (HIGHEST_PRECEDENCE + 1)의 뒤라 traceId 는 이미 있다.
         */
        @Bean
        public FilterRegistrationBean<UserMdcFilter> userMdcFilter() {
            FilterRegistrationBean<UserMdcFilter> registration = new FilterRegistrationBean<>(new UserMdcFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }
}
