package com.project.pay_service.ports.input.message.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
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
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.messaging.MessageHeaders;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxTraceContext;
import com.project.pay_service.message.config.KafkaConsumerConfig;

import io.micrometer.tracing.Tracer;

import com.project.common.tracing.OtelTestTracing;

/**
 * 🐛 결제 요청 뒤로 추적이 이어지지 않았다 — Zipkin 에 pay-service·"outbox relay payment-response" 가 없다.
 *
 * <p>{@code PaymentRequestTraceContinuationTest} 는 리스너 메서드를 <b>직접</b> 불러 통과했다. 그런데
 * 운영의 컨테이너 팩토리는 {@code setBatchToRecordAdapter(...)} 라, 스프링은 배치를 <b>레코드 하나씩</b>
 * 리스너에 넘기고 {@code BATCH_CONVERTED_HEADERS} 는 오지 않는다. 기대한 헤더가 null 이라 이어받기가
 * 아무것도 하지 않았다.
 *
 * <p>여기서는 <b>운영의 {@link KafkaConsumerConfig} 그대로</b> {@code @KafkaListener} 를 등록하고(브로커 없이,
 * 자동 시작만 끈다), 스프링이 만든 리스너 어댑터에 레코드를 넣는다. 결제 처리 순간의 현재 추적이 보낸
 * 쪽의 traceparent 와 같은지 본다.
 */
@DisplayName("pay-service — 운영 리스너 어댑터를 거쳐도 추적을 이어받는다")
class PaymentRequestListenerAdapterTraceTest {

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
    @Import({KafkaConsumerConfig.class, PaymentRequestKafaListener.class})
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
        PaymentRequestMessageListener paymentRequestMessageListener() {
            return mock(PaymentRequestMessageListener.class);
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

    /** order-service 의 릴레이가 보내는 모양 — Authorization 은 스프링 헤더 매퍼로, traceparent 는 프로듀서 추적이 바이트로. */
    private static ConsumerRecord<String, PaymentRequest> recordFromOrderService(PaymentRequest value) {
        RecordHeaders headers = new RecordHeaders();
        new DefaultKafkaHeaderMapper().fromHeaders(new MessageHeaders(Map.of("Authorization", "Bearer t")), headers);
        headers.add("traceparent", TRACEPARENT.getBytes(StandardCharsets.UTF_8));
        return new ConsumerRecord<>("order", 0, 0L, 0L, org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                0, 0, "1", value, headers, java.util.Optional.empty());
    }

    @Test
    @DisplayName("🔴 결제를 처리하는 순간의 추적이 order-service 가 보낸 traceparent 의 추적이다")
    @SuppressWarnings("unchecked")
    void paymentIsProcessedUnderTheSendersTrace() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kafka.url", "localhost:1")));
            ctx.register(Wiring.class);
            ctx.refresh();

            PaymentRequestMessageListener messages = ctx.getBean(PaymentRequestMessageListener.class);
            AtomicReference<String> traceSeen = new AtomicReference<>("(no trace)");
            doAnswer(i -> {
                var span = tracer.currentSpan();
                traceSeen.set(span == null ? "(no trace)" : span.context().traceId());
                return null;
            }).when(messages).completePayment(any());

            MessageListenerContainer container =
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-request");
            BatchMessageListener<String, PaymentRequest> adapter =
                    (BatchMessageListener<String, PaymentRequest>) container.getContainerProperties().getMessageListener();

            PaymentRequest request = PaymentRequest.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build();
            adapter.onMessage(List.of(recordFromOrderService(request)), null, null);

            assertThat(traceSeen.get()).as("결제 응답 행이 이 추적을 남긴다").isEqualTo(TRACE_ID);
        }
    }

    /**
     * 🔴 처리 실패는 삼키지 않는다 — 실패한 레코드의 위치를 실은 BatchListenerFailedException 이 에러 핸들러(재시도 · DLT · 경보)까지 간다.
     *
     * <p>🐛 예전 팩토리는 레코드 어댑터(DefaultBatchToRecordAdapter)를 둬서, 리스너의 예외를 잡아 필드에 담기만 했다 — 재시도도 DLT 도
     * 없이 오프셋이 커밋됐다(가드 B-88).
     */
    @Test
    @DisplayName("🔴 처리 실패는 실패 위치(두 번째)와 함께 에러 핸들러로 간다 — 예전에는 레코드 어댑터가 삼켰다")
    @SuppressWarnings("unchecked")
    void failureReachesTheErrorHandler() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kafka.url", "localhost:1")));
            ctx.register(Wiring.class);
            ctx.refresh();

            PaymentRequestMessageListener messages = ctx.getBean(PaymentRequestMessageListener.class);
            java.util.List<Long> handled = new java.util.ArrayList<>();
            doAnswer(i -> {
                PaymentRequest m = i.getArgument(0);
                if (m.getOrderId() == 2L) {
                    throw new org.springframework.dao.CannotAcquireLockException("db down");
                }
                handled.add(m.getOrderId());
                return null;
            }).when(messages).completePayment(any());

            BatchMessageListener<String, PaymentRequest> adapter = (BatchMessageListener<String, PaymentRequest>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-request")
                            .getContainerProperties().getMessageListener();
            List<ConsumerRecord<String, PaymentRequest>> batch = List.of(
                    new ConsumerRecord<>("order", 0, 0L, "1", PaymentRequest.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build()),
                    new ConsumerRecord<>("order", 0, 1L, "2", PaymentRequest.builder().orderId(2L).paymentStatus(PaymentStatus.COMPLETED).build()));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> adapter.onMessage(batch, null, null));
            // 어댑터는 ListenerExecutionFailedException 으로 감싼다 — 에러 핸들러는 원인 사슬에서 BatchListenerFailedException 을 찾는다.
            org.springframework.kafka.listener.BatchListenerFailedException failed = null;
            for (Throwable t = thrown; t != null && failed == null; t = t.getCause()) {
                if (t instanceof org.springframework.kafka.listener.BatchListenerFailedException b) {
                    failed = b;
                }
            }
            assertThat(failed).as("예외가 에러 핸들러로 가야 한다 — 삼키면 재시도 · DLT 가 없다").isNotNull();
            assertThat(failed.getIndex()).as("실패한 레코드의 위치 — 앞의 것은 커밋, 이것부터 재시도").isEqualTo(1);
            assertThat(handled).containsExactly(1L);
        }
    }

    /**
     * 🔴 PY3 — 같은 결제 요청을 동시에 처리한 늦은 쪽(결제 행 유니크 위반)은 "이미 처리됨" 으로 넘어간다. 다른 무결성 위반은 실패로 보낸다.
     * 🐛 예전에는 DuplicateKeyException 만 잡았는데 기본 Hibernate 는 DataIntegrityViolationException 을 내서 그 catch 는 한 번도 타지 않았다.
     * PY7 — 그룹 오프셋이 없으면 처음부터(earliest) 읽는다.
     */
    @Test
    @DisplayName("🔴 결제 행 유니크 위반은 이미 처리됨, 그 밖의 무결성 위반은 실패 · 오프셋 기본은 earliest")
    @SuppressWarnings("unchecked")
    void duplicatePaymentIsSkippedOtherIntegrityFails() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("kafka.url", "localhost:1")));
            ctx.register(Wiring.class);
            ctx.refresh();

            Object reset = ((org.springframework.kafka.core.DefaultKafkaConsumerFactory<?, ?>) ctx.getBean("paymentRequestConsumerFactory"))
                    .getConfigurationProperties().get(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
            assertThat(reset).isEqualTo("earliest");

            PaymentRequestMessageListener messages = ctx.getBean(PaymentRequestMessageListener.class);
            doAnswer(i -> {
                PaymentRequest m = i.getArgument(0);
                if (m.getOrderId() == 1L) {
                    throw new org.springframework.dao.DataIntegrityViolationException(
                            "could not execute statement",
                            new RuntimeException("ERROR: duplicate key value violates unique constraint \"uniqueorderidandtrackingid\""));
                }
                throw new org.springframework.dao.DataIntegrityViolationException("null value in column \"amounts\"");
            }).when(messages).completePayment(any());

            BatchMessageListener<String, PaymentRequest> adapter = (BatchMessageListener<String, PaymentRequest>)
                    ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-request")
                            .getContainerProperties().getMessageListener();
            List<ConsumerRecord<String, PaymentRequest>> batch = List.of(
                    new ConsumerRecord<>("order", 0, 0L, "1", PaymentRequest.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build()),
                    new ConsumerRecord<>("order", 0, 1L, "2", PaymentRequest.builder().orderId(2L).paymentStatus(PaymentStatus.COMPLETED).build()));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> adapter.onMessage(batch, null, null));
            org.springframework.kafka.listener.BatchListenerFailedException failed = null;
            for (Throwable t = thrown; t != null && failed == null; t = t.getCause()) {
                if (t instanceof org.springframework.kafka.listener.BatchListenerFailedException b) {
                    failed = b;
                }
            }
            assertThat(failed).as("다른 무결성 위반은 실패로").isNotNull();
            assertThat(failed.getIndex()).as("중복(첫 번째)은 넘어가고 두 번째에서 실패").isEqualTo(1);
        }
    }
}

