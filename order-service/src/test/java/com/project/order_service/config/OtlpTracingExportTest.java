package com.project.order_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

/**
 * 추적이 <b>실제로 OTLP 로 나가는가</b> — 공유 설정(application-common.yml) 그대로 (docs/63).
 *
 * <p>Brave · Zipkin 에서 OpenTelemetry 로 옮기면서 설정 키가 전부 바뀌었다({@code management.zipkin.*} →
 * {@code management.otlp.tracing.*}). 키 이름이 하나라도 틀리면 기동은 멀쩡하고 추적만 조용히 사라진다 —
 * Zipkin 시절에 파드 안의 {@code localhost:9411} 로 보내며 실제로 겪었다(B-59). 그래서 설정 파일을 읽어
 * 자동 설정을 띄우고, 가짜 수집기(JDK HTTP 서버)가 스팬을 받는지까지 본다.
 *
 * <p>수집기 주소는 k8s 매니페스트가 주는 환경 변수 이름({@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT})으로 넣는다 —
 * yml 의 자리표시자 이름과 매니페스트의 이름이 어긋나도 여기서 걸린다.
 */
class OtlpTracingExportTest {

    private HttpServer collector;
    private final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeCollector() throws IOException {
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/", exchange -> {
            Map<String, Object> req = new HashMap<>();
            req.put("method", exchange.getRequestMethod());
            req.put("path", exchange.getRequestURI().getPath());
            req.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            // protobuf 본문 — 문자열 값(서비스 이름 · 스팬 이름)은 UTF-8 그대로 들어 있다.
            req.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            received.add(req);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
    }

    @AfterEach
    void stopFakeCollector() {
        collector.stop(0);
    }

    private ApplicationContextRunner runner() throws IOException {
        List<PropertySource<?>> common = new YamlPropertySourceLoader()
                .load("application-common", new ClassPathResource("application-common.yml"));
        return new ApplicationContextRunner()
                .withInitializer(ctx -> common.forEach(ps -> ctx.getEnvironment().getPropertySources().addLast(ps)))
                .withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class,
                        OpenTelemetryTracingAutoConfiguration.class, OtlpTracingAutoConfiguration.class))
                .withPropertyValues("spring.application.name=order-service",
                        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:" + collector.getAddress().getPort()
                                + "/v1/traces");
    }

    @Test
    @DisplayName("공유 설정만으로 OTLP/HTTP 내보내기가 켜지고, 스팬이 /v1/traces 로 protobuf 로 간다")
    void spansReachTheCollector() throws IOException {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(OtlpHttpSpanExporter.class);

            Span span = ctx.getBean(Tracer.class).nextSpan().name("otlp-wiring-probe").start();
            span.end();
            ctx.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);

            assertThat(received).as("가짜 수집기가 받은 요청").hasSize(1);
            Map<String, Object> req = received.get(0);
            assertThat(req.get("method")).isEqualTo("POST");
            assertThat(req.get("path")).isEqualTo("/v1/traces");
            assertThat(req.get("contentType")).isEqualTo("application/x-protobuf");
            String body = (String) req.get("body");
            // Jaeger · span_metrics 는 resource 의 service.name 으로 서비스를 가른다 — spring.application.name 이어야 한다.
            assertThat(body).contains("order-service").contains("otlp-wiring-probe");
            assertThat(body).contains("deployment.environment");
        });
    }

    @Test
    @DisplayName("전파 형식은 W3C traceparent — 모든 서비스(와 Kafka 헤더)가 같은 형식이어야 추적이 이어진다")
    void propagatesW3cTraceContext() throws IOException {
        runner().run(ctx -> {
            Span span = ctx.getBean(Tracer.class).nextSpan().name("propagation-probe").start();
            Map<String, String> carrier = new HashMap<>();
            ctx.getBean(Propagator.class).inject(span.context(), carrier, Map::put);
            span.end();

            assertThat(carrier).containsKey("traceparent");
            assertThat(carrier.get("traceparent")).startsWith("00-" + span.context().traceId() + "-");
            assertThat(carrier).doesNotContainKey("X-B3-TraceId").doesNotContainKey("b3");
        });
    }
}
