package com.project.delivery_service.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxTraceContext;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.config.KafkaConsumerConfig;
import com.project.delivery_service.service.DeliveryService;

import io.micrometer.tracing.Tracer;

import com.project.common.tracing.OtelTestTracing;

/**
 * 결제 → 배송 생성이 결제의 추적에 붙는가 — <b>레코드 어댑터가 없는 보통 배치 팩토리</b>의 대표다(docs/51 §4h).
 *
 * <p>사가 리스너 둘(pay·order)은 레코드 어댑터라 레코드 헤더가 오고, 나머지 배치 리스너는 스프링의 배치
 * 변환기가 {@code BATCH_CONVERTED_HEADERS} 로 레코드별 헤더를 넘긴다. 이 테스트는 뒤쪽을 운영 설정 그대로
 * 본다: 운영 {@link KafkaConsumerConfig} 로 {@code @KafkaListener} 를 등록하고(자동 시작만 끈다), 서로 다른
 * 추적의 레코드 두 건을 <b>한 배치로</b> 넣어 각 레코드가 자기 추적 안에서 처리되는지 확인한다.
 */
@DisplayName("delivery-service — 배치 안의 레코드마다 자기 추적을 이어받는다")
class PaymentCompletedListenerAdapterTraceTest {

    private static final String TRACE_A = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACE_B = "0af7651916cd43dd8448eb211c80319c";

    private static OtelTestTracing tracing;
    private static Tracer tracer;

    @AfterEach
    void close() {
        if (tracing != null) {
            tracing.close();
        }
    }

    @Configuration
    @Import({KafkaConsumerConfig.class, PaymentCompletedListener.class})
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
        DeliveryService deliveryService() {
            return mock(DeliveryService.class);
        }

        @Bean
        OrderServiceClient orderServiceClient() {
            return mock(OrderServiceClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
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

    private static ConsumerRecord<String, String> record(long offset, long orderId, String traceId) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("traceparent", ("00-" + traceId + "-00f067aa0ba902b7-01").getBytes(StandardCharsets.UTF_8));
        String value = "{\"orderId\":" + orderId + ",\"customerId\":7,\"paymentStatus\":\"CANCELED\"}";
        return new ConsumerRecord<>("payment", 0, offset, 0L, TimestampType.CREATE_TIME,
                0, 0, "k", value, headers, Optional.empty());
    }

    @Test
    @DisplayName("🔴 한 배치의 두 레코드가 각자 보낸 쪽의 추적 안에서 처리된다")
    @SuppressWarnings("unchecked")
    void eachRecordInTheBatchRunsUnderItsOwnTrace() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kafka.url", "localhost:1")));
            ctx.register(Wiring.class);
            ctx.refresh();

            List<String> seen = new ArrayList<>();
            when(ctx.getBean(DeliveryService.class).cancelByOrder(anyLong())).thenAnswer(i -> {
                var span = tracer.currentSpan();
                seen.add(i.getArgument(0) + ":" + (span == null ? "(no trace)" : span.context().traceId()));
                return false;
            });

            BatchMessageListener<String, String> adapter = (BatchMessageListener<String, String>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("delivery-payment")
                            .getContainerProperties().getMessageListener();
            adapter.onMessage(List.of(record(0, 1, TRACE_A), record(1, 2, TRACE_B)), null, null);

            assertThat(seen).containsExactly("1:" + TRACE_A, "2:" + TRACE_B);
            assertThat(tracer.currentSpan()).as("스코프가 닫혔다").isNull();
        }
    }
}
