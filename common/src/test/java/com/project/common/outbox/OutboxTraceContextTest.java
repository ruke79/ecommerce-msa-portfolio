package com.project.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;

import com.project.common.tracing.OtelTestTracing;

/**
 * 아웃박스를 건너도 추적이 이어지는가 — <b>운영과 같은 OpenTelemetry 추적기와 실제 KafkaTemplate</b> 로 본다.
 *
 * <p>요청 스레드에서 행을 쓰고(capture), 다른 스레드의 스케줄러가 몇 초 뒤 보낸다(resume). 그 사이
 * 스레드의 추적 문맥은 없다. 확인하는 것은 하나다: <b>Kafka 로 나간 레코드의 추적 헤더가 원래 요청의
 * traceId 를 싣고 있는가.</b> 그래야 받는 서비스의 리스너가 같은 추적에 자식으로 붙는다.
 */
@DisplayName("OutboxTraceContext — 아웃박스를 건너는 추적")
class OutboxTraceContextTest {

    private OtelTestTracing tracing;
    private Tracer tracer;
    private Propagator propagator;
    private OutboxTraceContext outbox;

    @BeforeEach
    void setUp() {
        tracing = new OtelTestTracing();
        tracer = tracing.tracer();
        propagator = tracing.propagator();
        outbox = new OutboxTraceContext(() -> tracer, () -> propagator);
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    private List<SpanData> finished() {
        return tracing.finishedSpans();
    }

    /** 요청 스레드에서 행을 쓰는 순간. */
    private String captureInsideRequest(String[] traceIdHolder) {
        Span request = tracer.nextSpan().name("POST /api/order/payment/paypal").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            traceIdHolder[0] = request.context().traceId();
            return outbox.capture();
        } finally {
            request.end();
        }
    }

    /** 프로듀서 추적이 켜진 KafkaTemplate — 운영과 같은 핸들러 구성. */
    private KafkaTemplate<String, String> tracedTemplate(MockProducer<String, String> producer) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                        new DefaultTracingObservationHandler(tracer)));
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new MockProducerFactory<>(() -> producer));
        template.setObservationEnabled(true);
        template.setObservationRegistry(registry);
        return template;
    }

    private static String headers(ProducerRecord<String, String> record) {
        StringBuilder sb = new StringBuilder();
        for (Header h : record.headers()) {
            sb.append(h.key()).append('=').append(new String(h.value(), StandardCharsets.UTF_8)).append(' ');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("🔴 요청에서 저장한 문맥으로 스케줄러가 보내면 Kafka 헤더에 원래 요청의 traceId 가 실린다")
    void relayedRecordCarriesTheOriginalTrace() throws Exception {
        String[] traceId = new String[1];
        String stored = captureInsideRequest(traceId);
        assertThat(stored).as("저장된 추적 문맥").isNotBlank().contains(traceId[0]);

        // 스케줄러 스레드 — 추적 문맥이 없다.
        assertThat(tracer.currentSpan()).isNull();
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> template = tracedTemplate(producer);

        outbox.resume(stored, "outbox relay payment-request", () -> {
            try {
                return template.send("order", "42", "{}").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(producer.history()).hasSize(1);
        assertThat(headers(producer.history().get(0)))
                .as("받는 쪽이 같은 추적에 붙으려면 헤더에 원래 traceId 가 있어야 한다")
                .contains(traceId[0]);
        // 되살린 릴레이 스팬과 프로듀서 스팬 모두 원래 추적 안에 있다.
        assertThat(finished()).extracting(SpanData::getTraceId).containsOnly(traceId[0]);
        assertThat(finished()).extracting(SpanData::getName).contains("outbox relay payment-request");
    }

    /** 고치기 전의 모습 — 대조군. 이게 통과해야 위 테스트가 무엇을 증명하는지 분명하다. */
    @Test
    @DisplayName("대조: 문맥 없이 보내면 새 추적이 시작된다 — 예전의 끊김")
    void withoutStoredContextTheTraceStartsOver() throws Exception {
        String[] traceId = new String[1];
        captureInsideRequest(traceId);

        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        tracedTemplate(producer).send("order", "42", "{}").get(5, TimeUnit.SECONDS);

        assertThat(headers(producer.history().get(0))).doesNotContain(traceId[0]);
    }

    /**
     * 🐛 결제 요청까지만 이어지고 결제 응답부터 끊겼다. pay-service 의 리스너가 <b>배치</b>라 컨슈머
     * 추적이 걸리지 않았고, 결제 응답 행을 쓸 때 현재 추적이 없었다. 여기서 pay-service 의 순서를 그대로
     * 밟는다: 추적이 실린 레코드 → <b>스프링의 실제 배치 변환기</b> → 레코드 헤더로 이어받기 → 그 안에서
     * 다음 아웃박스 행의 문맥 저장.
     */
    @Test
    @DisplayName("🔴 배치 리스너: 레코드 헤더를 이어받은 안에서 쓴 아웃박스 행은 원래 추적을 남긴다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchListenerContinuesTheTraceIntoTheNextOutboxRow() throws Exception {
        // order-service: 요청 → 아웃박스 → 릴레이가 보낸 레코드
        String[] traceId = new String[1];
        String stored = captureInsideRequest(traceId);
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        KafkaTemplate<String, String> template = tracedTemplate(producer);
        outbox.resume(stored, "outbox relay payment-request", () -> {
            try {
                return template.send("order", "42", "{}").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ProducerRecord<String, String> sent = producer.history().get(0);

        // pay-service: 배치로 받는다 — 리스너가 보는 것은 BATCH_CONVERTED_HEADERS 다.
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> received =
                new org.apache.kafka.clients.consumer.ConsumerRecord<>("order", 0, 0L, "42", "{}");
        sent.headers().forEach(h -> received.headers().add(h));
        org.springframework.messaging.Message<?> batch =
                new org.springframework.kafka.support.converter.BatchMessagingMessageConverter()
                        .toMessage((List) List.of(received), null, null, null);
        List<java.util.Map<String, Object>> batchHeaders = (List<java.util.Map<String, Object>>)
                batch.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.BATCH_CONVERTED_HEADERS);
        assertThat(batchHeaders).as("기본 변환기는 레코드별 헤더를 넘긴다").hasSize(1);

        assertThat(tracer.currentSpan()).as("리스너 스레드 — 추적이 없다").isNull();
        String[] insideTrace = new String[1];
        String nextRow = outbox.continueFrom(OutboxTraceContext.headersAt(batchHeaders, 0), "receive payment-request",
                () -> {
                    insideTrace[0] = tracer.currentSpan().context().traceId();
                    return outbox.capture();   // 결제 응답 아웃박스 행을 쓰는 순간
                });

        assertThat(insideTrace[0]).as("리스너 안의 추적").isEqualTo(traceId[0]);
        assertThat(nextRow).as("결제 응답 행에 남는 문맥").contains(traceId[0]);
        assertThat(tracer.currentSpan()).as("스코프가 닫혔다").isNull();
    }

    /** 레코드 어댑터(setBatchToRecordAdapter)면 메시지 헤더가 곧 그 레코드의 헤더다. 배치 변환이면 목록의 i 번째. */
    @Test
    @DisplayName("recordHeaders — 레코드 어댑터와 배치 변환 두 모양을 다 받는다")
    void recordHeadersAcceptsBothShapes() {
        java.util.Map<String, Object> record = java.util.Map.of("traceparent", "x".getBytes(), "Authorization", "Bearer t");
        assertThat(OutboxTraceContext.recordHeaders(record, 0)).isSameAs(record);
        assertThat(OutboxTraceContext.recordHeaders(record, 1)).isNull();

        java.util.Map<String, Object> first = java.util.Map.of("traceparent", "a");
        java.util.Map<String, Object> second = java.util.Map.of("traceparent", "b");
        java.util.Map<String, Object> batch = java.util.Map.of(
                org.springframework.kafka.support.KafkaHeaders.BATCH_CONVERTED_HEADERS, List.of(first, second));
        assertThat(OutboxTraceContext.recordHeaders(batch, 1)).isSameAs(second);
        assertThat(OutboxTraceContext.recordHeaders(batch, 2)).isNull();
        assertThat(OutboxTraceContext.recordHeaders(null, 0)).isNull();
    }

    @Test
    @DisplayName("헤더가 없거나 인덱스가 벗어나면 그냥 실행한다")
    void continueFromWithoutHeadersJustRuns() {
        assertThat(outbox.continueFrom(null, "x", () -> "done")).isEqualTo("done");
        assertThat(outbox.continueFrom(java.util.Map.of(), "x", () -> "done")).isEqualTo("done");
        assertThat(OutboxTraceContext.headersAt(null, 0)).isNull();
        assertThat(OutboxTraceContext.headersAt(List.of(java.util.Map.of("a", "b")), 3)).isNull();
    }

    @Test
    @DisplayName("추적이 없으면 저장하지 않고, 저장된 게 없거나 깨져 있어도 발행은 그대로 한다")
    void neverBlocksPublishing() {
        assertThat(outbox.capture()).as("현재 스팬이 없다").isNull();
        assertThat(OutboxTraceContext.NOOP.capture()).isNull();

        assertThat(outbox.resume(null, "x", () -> "sent")).isEqualTo("sent");
        assertThat(outbox.resume("", "x", () -> "sent")).isEqualTo("sent");
        assertThat(outbox.resume("garbage without equals\n=\nb3=zz-not-hex", "x", () -> "sent")).isEqualTo("sent");
        assertThat(OutboxTraceContext.NOOP.resume("b3=abc", "x", () -> "sent")).isEqualTo("sent");
    }

    @Test
    @DisplayName("보내다 실패하면 예외는 그대로 올라가고 스팬은 닫힌다 — 릴레이가 실패를 처리한다")
    void failurePropagatesAndSpanEnds() {
        String[] traceId = new String[1];
        String stored = captureInsideRequest(traceId);
        int before = finished().size();

        try {
            outbox.resume(stored, "outbox relay", () -> {
                throw new IllegalStateException("broker down");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("broker down");
        }
        assertThat(finished()).hasSize(before + 1);
        assertThat(finished().get(finished().size() - 1).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tracer.currentSpan()).as("스코프가 닫혔다").isNull();
    }
}
