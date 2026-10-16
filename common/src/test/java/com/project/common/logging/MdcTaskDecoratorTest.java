package com.project.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 스레드풀로 넘긴 작업에 로그 문맥이 따라가는가.
 *
 * <p>MDC 는 스레드마다 따로라, 메일 발송·SSE·상품 가져오기·재색인 로그에서 traceId·userId 가
 * 비어 요청과 이을 수 없었다.
 */
@DisplayName("MdcTaskDecorator")
class MdcTaskDecoratorTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();   // 한 스레드 — 재사용될 때 섞이는지 보려고
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        MDC.clear();
    }

    private String seenOnWorker(Runnable wrapped, AtomicReference<String> sink) throws Exception {
        pool.submit(wrapped).get(5, TimeUnit.SECONDS);
        return sink.get();
    }

    @Test
    @DisplayName("🔴 넘기는 순간의 traceId·userId 가 작업 스레드에서 보인다")
    void carriesContext() throws Exception {
        MDC.put("traceId", "t-1");
        MDC.put("userId", "7");
        AtomicReference<String> seen = new AtomicReference<>();

        String got = seenOnWorker(MdcTaskDecorator.wrap(
                () -> seen.set(MDC.get("traceId") + "/" + MDC.get("userId"))), seen);

        assertThat(got).isEqualTo("t-1/7");
    }

    @Test
    @DisplayName("🔴 재사용되는 풀 스레드가 앞 작업의 traceId 를 달고 다니지 않는다")
    void doesNotLeakBetweenTasks() throws Exception {
        MDC.put("traceId", "t-1");
        pool.submit(MdcTaskDecorator.wrap(() -> { })).get(5, TimeUnit.SECONDS);

        MDC.clear();   // 두 번째 요청에는 문맥이 없다
        AtomicReference<String> seen = new AtomicReference<>();
        String got = seenOnWorker(MdcTaskDecorator.wrap(() -> seen.set(String.valueOf(MDC.get("traceId")))), seen);

        assertThat(got).isEqualTo("null");
    }

    /**
     * 🔴 넘기는 순간의 <b>관측(추적 문맥)</b>도 작업 스레드로 간다. 예전에는 MDC 만 복사해, 작업 스레드에서 관측을 켠 KafkaTemplate 으로
     * 보내면(가입 메일 요청) 새 루트 추적이 시작돼 요청과 이어지지 않았다.
     */
    @Test
    @DisplayName("🔴 요청의 관측(추적)이 작업 스레드에서도 현재 span 이다 — 작업 안에서 시작한 span 은 그 자식")
    void observationFollowsTheTask() throws Exception {
        try (com.project.common.tracing.OtelTestTracing tracing = new com.project.common.tracing.OtelTestTracing()) {
            io.micrometer.tracing.Tracer tracer = tracing.tracer();
            io.micrometer.observation.ObservationRegistry registry = io.micrometer.observation.ObservationRegistry.create();
            registry.observationConfig().observationHandler(
                    new io.micrometer.tracing.handler.DefaultTracingObservationHandler(tracer));
            io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor accessor =
                    io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor.getInstance();
            io.micrometer.observation.ObservationRegistry previous = accessor.getObservationRegistry();
            accessor.setObservationRegistry(registry);
            try {
                AtomicReference<String> childTrace = new AtomicReference<>();
                String requestTrace;
                Runnable wrapped;
                io.micrometer.observation.Observation request =
                        io.micrometer.observation.Observation.start("POST /api/auth/register", registry);
                try (io.micrometer.observation.Observation.Scope ignored = request.openScope()) {
                    requestTrace = tracer.currentSpan().context().traceId();
                    wrapped = new MdcTaskDecorator().decorate(() -> {
                        io.micrometer.tracing.Span child = tracer.nextSpan().name("send outgoing-mail").start();
                        childTrace.set(child.context().traceId());
                        child.end();
                    });
                }
                pool.submit(wrapped).get(5, TimeUnit.SECONDS);
                request.stop();

                assertThat(childTrace.get()).as("새 루트가 아니라 요청의 추적").isEqualTo(requestTrace);
            } finally {
                accessor.setObservationRegistry(previous);
            }
        }
    }

    @Test
    @DisplayName("작업 스레드의 원래 문맥은 작업이 끝나면 돌아온다")
    void restoresWorkerContext() throws Exception {
        pool.submit(() -> MDC.put("worker", "w")).get(5, TimeUnit.SECONDS);   // 감싸지 않은 작업이 남긴 값

        MDC.put("traceId", "t-2");
        pool.submit(MdcTaskDecorator.wrap(() -> { })).get(5, TimeUnit.SECONDS);

        AtomicReference<String> seen = new AtomicReference<>();
        pool.submit(() -> seen.set(MDC.get("worker") + "/" + MDC.get("traceId"))).get(5, TimeUnit.SECONDS);
        assertThat(seen.get()).isEqualTo("w/null");
    }

    /**
     * 공통 자동 구성이 등록돼 있어야 서비스마다 붙는다. 대부분의 서비스가 com.project.common 을
     * 스캔하지 않으므로 스캔에 기대면 서비스마다 붙기도 하고 안 붙기도 한다.
     */
    @Test
    @DisplayName("자동 구성이 등록돼 있다 — 스캔하지 않는 서비스에도 붙는다")
    void autoConfigurationIsRegistered() throws Exception {
        Path imports = Path.of("src/main/resources/META-INF/spring/"
                + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(Files.readString(imports, StandardCharsets.UTF_8))
                .contains(CommonLoggingAutoConfiguration.class.getName());
    }
}
