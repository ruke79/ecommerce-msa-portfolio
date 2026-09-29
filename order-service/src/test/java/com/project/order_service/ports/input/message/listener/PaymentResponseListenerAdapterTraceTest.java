package com.project.order_service.ports.input.message.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.messaging.MessageHeaders;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.config.KafkaConsumerConfig;

import io.micrometer.tracing.Tracer;

import com.project.common.tracing.OtelTestTracing;

/**
 * 🐛 결제 응답을 받아 주문을 확정하는 순간 추적이 없었다 — 운영 팩토리가 레코드 어댑터라 배치 헤더가
 * 오지 않았다(pay-service 의 {@code PaymentRequestListenerAdapterTraceTest} 와 같은 원인, docs/51 §4g).
 *
 * <p>운영의 {@link KafkaConsumerConfig} 그대로 {@code @KafkaListener} 를 등록하고(자동 시작만 끈다),
 * 스프링이 만든 어댑터에 pay-service 가 보내는 모양의 레코드를 넣는다.
 */
@DisplayName("order-service — 운영 리스너 어댑터를 거쳐도 결제 응답의 추적을 이어받는다")
class PaymentResponseListenerAdapterTraceTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private static OtelTestTracing tracing;
    private static Tracer tracer;

    @AfterEach
    void close() {
        if (tracing != null) {
            tracing.close();
        }
    }

    @Configuration
    @Import({KafkaConsumerConfig.class, PaymentResponseKafaListener.class})
    static class Wiring {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DefaultErrorHandler kafkaErrorHandler() {
            return new DefaultErrorHandler();
        }

        @Bean
        PaymentResponseMessageListener paymentResponseMessageListener() {
            return mock(PaymentResponseMessageListener.class);
        }

        @Bean
        OutboxTraceContext outboxTraceContext() {
            // 운영과 같은 OpenTelemetry 브리지 · W3C 전파(docs/63) — traceparent 를 그대로 읽는다.
            tracing = new OtelTestTracing();
            tracer = tracing.tracer();
            io.micrometer.tracing.propagation.Propagator propagator = tracing.propagator();
            return new OutboxTraceContext(() -> tracer, () -> propagator);
        }

        /** 브로커 없이 — 컨테이너를 만들되 시작하지 않는다. 팩토리 설정은 운영 그대로다. */
        @Bean
        static BeanPostProcessor noAutoStartup() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> f) {
                        f.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }

    @Test
    @DisplayName("🔴 주문을 확정하는 순간의 추적이 pay-service 가 보낸 traceparent 의 추적이다")
    @SuppressWarnings("unchecked")
    void orderIsSettledUnderTheSendersTrace() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kafka.url", "localhost:1")));
            ctx.register(Wiring.class);
            ctx.refresh();

            PaymentResponseMessageListener messages = ctx.getBean(PaymentResponseMessageListener.class);
            AtomicReference<String> traceSeen = new AtomicReference<>("(not called)");
            doAnswer(i -> {
                var span = tracer.currentSpan();
                traceSeen.set(span == null ? "(no trace)" : span.context().traceId());
                return null;
            }).when(messages).paymentCompleted(any());

            BatchMessageListener<String, PaymentResponse> adapter = (BatchMessageListener<String, PaymentResponse>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-response")
                            .getContainerProperties().getMessageListener();

            RecordHeaders headers = new RecordHeaders();
            new DefaultKafkaHeaderMapper().fromHeaders(new MessageHeaders(Map.of("Authorization", "Bearer t")), headers);
            headers.add("traceparent", TRACEPARENT.getBytes(StandardCharsets.UTF_8));
            PaymentResponse response = PaymentResponse.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build();
            adapter.onMessage(List.of(new ConsumerRecord<>("payment", 0, 0L, 0L, TimestampType.CREATE_TIME,
                    0, 0, "1", response, headers, Optional.empty())), null, null);

            assertThat(traceSeen.get()).isEqualTo(TRACE_ID);
        }
    }
}
