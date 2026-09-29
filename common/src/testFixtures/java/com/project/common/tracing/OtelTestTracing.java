package com.project.common.tracing;

import java.util.Collections;
import java.util.List;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * 추적 테스트용 — <b>운영과 같은</b> OpenTelemetry 브리지(Micrometer Tracing OTel)와 W3C 전파.
 *
 * <p>끝난 span 은 내보내지 않고 메모리에 모은다({@link #finishedSpans()}). 테스트마다 새로 만들고 {@link #close()} 한다.
 *
 * <p>예전에는 테스트마다 Brave 추적기를 손으로 조립했다(운영도 Brave 였다). 운영을 OpenTelemetry 로 옮기면서(docs/63)
 * 테스트가 다른 추적기로 돌면 "테스트는 초록, 운영은 헤더가 안 이어짐" 이 될 수 있어 같은 브리지로 맞췄다.
 */
public final class OtelTestTracing implements AutoCloseable {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider;
    private final Tracer tracer;
    private final Propagator propagator;

    public OtelTestTracing() {
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        ContextPropagators propagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).setPropagators(propagators).build();
        io.opentelemetry.api.trace.Tracer otel = sdk.getTracer("test");
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        tracer = new OtelTracer(otel, current, event -> { },
                new OtelBaggageManager(current, Collections.emptyList(), Collections.emptyList()));
        propagator = new OtelPropagator(propagators, otel);
    }

    public Tracer tracer() {
        return tracer;
    }

    public Propagator propagator() {
        return propagator;
    }

    /** 끝난 span(이름 · traceId · 부모). */
    public List<SpanData> finishedSpans() {
        return exporter.getFinishedSpanItems();
    }

    @Override
    public void close() {
        provider.close();
    }
}
