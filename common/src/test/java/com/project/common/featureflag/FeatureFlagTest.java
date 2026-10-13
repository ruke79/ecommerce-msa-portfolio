package com.project.common.featureflag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.project.common.tracing.OtelTestTracing;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * 기능 플래그(docs/74) — 정의 파일 · 파일 공급자 · 관측 훅.
 * [JA] 機能フラグ(docs/74) — 定義ファイル・ファイルプロバイダー・観測フック。
 */
class FeatureFlagTest {

    @TempDir Path dir;

    private OpenFeatureAPI api;
    private OtelTestTracing tracing;

    @AfterEach
    void tearDown() {
        if (api != null) api.shutdown();
        if (tracing != null) tracing.close();
    }

    static String searchFlag(int esWeight, int pgWeight) {
        return """
            { "flags": { "catalog.search-engine": {
                "state": "ENABLED",
                "variants": { "es": "es", "postgres": "postgres" },
                "defaultVariant": "postgres",
                "targeting": { "fractional": [ ["es", %d], ["postgres", %d] ] },
                "metadata": { "owner": "catalog", "expires": "2027-03-31" } } } }
            """.formatted(esWeight, pgWeight);
    }

    // ---------------------------------------------------------------- 정의 파일

    @Test
    @DisplayName("비율 배정은 대상 키마다 고정이고, 비중대로 나뉜다")
    void fractionalIsStickyAndProportional() {
        FlagDefinitions.Flag f = FlagDefinitions.parse(searchFlag(10, 90)).get("catalog.search-engine");

        long es = IntStream.range(0, 10_000).mapToObj(i -> "term-" + i)
                .filter(k -> f.resolve(k).variant().equals("es")).count();
        assertThat(es).isBetween(850L, 1150L);
        assertThat(f.resolve("nike").variant()).isEqualTo(f.resolve("nike").variant());
        assertThat(f.resolve("nike").reason()).isEqualTo("SPLIT");
    }

    @Test
    @DisplayName("비율을 10 → 30 으로 올려도 처음 10% 에 들었던 대상은 그대로 es 다")
    void raisingTheWeightKeepsEarlierTargets() {
        FlagDefinitions.Flag ten = FlagDefinitions.parse(searchFlag(10, 90)).get("catalog.search-engine");
        FlagDefinitions.Flag thirty = FlagDefinitions.parse(searchFlag(30, 70)).get("catalog.search-engine");

        IntStream.range(0, 2_000).mapToObj(i -> "term-" + i)
                .filter(k -> ten.resolve(k).variant().equals("es"))
                .forEach(k -> assertThat(thirty.resolve(k).variant()).as(k).isEqualTo("es"));
    }

    @Test
    @DisplayName("대상 키가 없으면 비율 배정을 하지 않고 기본 변형이다")
    void noTargetingKeyMeansDefault() {
        FlagDefinitions.Flag f = FlagDefinitions.parse(searchFlag(100, 0)).get("catalog.search-engine");
        assertThat(f.resolve(null)).isEqualTo(new FlagDefinitions.Resolution("postgres", "DEFAULT"));
    }

    @Test
    @DisplayName("🔴 모르는 targeting 은 거부한다 — 조용히 무시하면 '일부에만' 이 전체로 돈다")
    void unknownTargetingIsRejected() {
        String json = """
            { "flags": { "f": { "state": "ENABLED", "variants": { "on": true, "off": false },
              "defaultVariant": "on",
              "targeting": { "if": [ { "in": ["@corp", { "var": "email" }] }, "on", "off" ] } } } }
            """;
        assertThatThrownBy(() -> FlagDefinitions.parse(json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fractional");
    }

    @Test
    @DisplayName("defaultVariant · fractional 의 변형이 variants 에 없으면 거부한다")
    void unknownVariantIsRejected() {
        assertThatThrownBy(() -> FlagDefinitions.parse(searchFlag(10, 90).replace("\"defaultVariant\": \"postgres\"",
                "\"defaultVariant\": \"pg\""))).hasMessageContaining("defaultVariant");
        assertThatThrownBy(() -> FlagDefinitions.parse(searchFlag(10, 90).replace("[\"es\", 10]", "[\"opensearch\", 10]")))
                .hasMessageContaining("opensearch");
    }

    // ---------------------------------------------------------------- 파일 공급자

    private Client client(FileFlagProvider provider, Tracer tracer, SimpleMeterRegistry meters) {
        api = OpenFeatureAPI.createIsolated();
        api.addHooks(new FeatureFlagObservabilityHook(() -> tracer, () -> meters));
        api.setProviderAndWait(provider);
        return api.getClient("test");
    }

    @Test
    @DisplayName("파일이 없으면 코드의 기본값 — 이유는 ERROR(FLAG_NOT_FOUND)")
    void missingFileFallsBackToCodeDefault() {
        Client c = client(new FileFlagProvider(dir.resolve("nope.json"), Duration.ofSeconds(5)), null, null);

        FlagEvaluationDetails<String> d = c.getStringDetails("catalog.search-engine", "es", new ImmutableContext("nike"));
        assertThat(d.getValue()).isEqualTo("es");
        assertThat(d.getErrorCode()).hasToString("FLAG_NOT_FOUND");
    }

    @Test
    @DisplayName("🔴 파일을 바꾸면 재시작 없이 다음 확인 때 반영된다 — 틀린 내용이면 직전 정의를 유지한다")
    void reloadsWithoutRestartAndKeepsLastGoodOnError() throws Exception {
        Path f = dir.resolve("flags.json");
        Files.writeString(f, searchFlag(0, 100), StandardCharsets.UTF_8);
        AtomicLong now = new AtomicLong(0);
        Client c = client(new FileFlagProvider(f, Duration.ofSeconds(5), now::get), null, null);
        ImmutableContext nike = new ImmutableContext("nike");

        assertThat(c.getStringValue("catalog.search-engine", "x", nike)).isEqualTo("postgres");

        Files.writeString(f, searchFlag(100, 0), StandardCharsets.UTF_8);
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(c.getStringValue("catalog.search-engine", "x", nike)).as("간격 전에는 그대로").isEqualTo("postgres");
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(c.getStringValue("catalog.search-engine", "x", nike)).as("간격 뒤에는 새 정의").isEqualTo("es");

        Files.writeString(f, "{ \"flags\": { oops", StandardCharsets.UTF_8);
        now.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThat(c.getStringValue("catalog.search-engine", "x", nike)).as("틀린 파일 → 직전 정의").isEqualTo("es");
    }

    @Test
    @DisplayName("DISABLED 플래그는 코드의 기본값 — 이유 DISABLED")
    void disabledFlagReturnsCodeDefault() throws Exception {
        Path f = dir.resolve("flags.json");
        Files.writeString(f, searchFlag(100, 0).replace("ENABLED", "DISABLED"), StandardCharsets.UTF_8);
        Client c = client(new FileFlagProvider(f, Duration.ofSeconds(5)), null, null);

        FlagEvaluationDetails<String> d = c.getStringDetails("catalog.search-engine", "postgres", new ImmutableContext("nike"));
        assertThat(d.getValue()).isEqualTo("postgres");
        assertThat(d.getReason()).isEqualTo("DISABLED");
    }

    // ---------------------------------------------------------------- 자동 구성

    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(FeatureFlagAutoConfiguration.class));

    @Test
    @DisplayName("자동 구성 — FEATURE_FLAGS_FILE(feature-flags.file)이 있으면 파일 공급자, 없으면 코드의 기본값")
    void autoConfiguration() throws Exception {
        Path f = dir.resolve("flags.json");
        Files.writeString(f, searchFlag(100, 0), StandardCharsets.UTF_8);

        runner.withPropertyValues("feature-flags.file=" + f).run(ctx -> {
            assertThat(ctx.getBean(OpenFeatureAPI.class).getProvider()).isInstanceOf(FileFlagProvider.class);
            assertThat(ctx.getBean(Client.class).getStringValue("catalog.search-engine", "postgres",
                    new ImmutableContext("nike"))).isEqualTo("es");
        });
        runner.run(ctx -> assertThat(ctx.getBean(Client.class).getStringValue("catalog.search-engine", "postgres",
                new ImmutableContext("nike"))).isEqualTo("postgres"));
    }

    // ---------------------------------------------------------------- 관측 훅

    @Test
    @DisplayName("🔴 평가 결과가 지금 스팬의 태그와 지표로 남는다 — 대상 키(검색어)는 남지 않는다")
    void evaluationIsRecordedOnTheCurrentSpan() throws Exception {
        Path f = dir.resolve("flags.json");
        Files.writeString(f, searchFlag(100, 0), StandardCharsets.UTF_8);
        tracing = new OtelTestTracing();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Client c = client(new FileFlagProvider(f, Duration.ofSeconds(5)), tracing.tracer(), meters);

        Span span = tracing.tracer().nextSpan().name("GET /api/search").start();
        try (Tracer.SpanInScope ignored = tracing.tracer().withSpan(span)) {
            c.getStringValue("catalog.search-engine", "postgres", new ImmutableContext("secret search term"));
        } finally {
            span.end();
        }

        SpanData s = tracing.finishedSpans().get(0);
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.key"))).isEqualTo("catalog.search-engine");
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.result.variant"))).isEqualTo("es");
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.result.reason"))).isEqualTo("SPLIT");
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.provider.name"))).isEqualTo("file");
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.variant.catalog.search-engine"))).isEqualTo("es");
        assertThat(s.getAttributes().asMap().values()).noneMatch(v -> String.valueOf(v).contains("secret"));

        assertThat(meters.get("feature_flag.evaluations").tag("key", "catalog.search-engine")
                .tag("variant", "es").tag("reason", "SPLIT").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("평가가 실패해도(플래그 없음) 스팬에 오류 코드가 남는다")
    void errorIsRecordedToo() {
        tracing = new OtelTestTracing();
        Client c = client(new FileFlagProvider(dir.resolve("nope.json"), Duration.ofSeconds(5)), tracing.tracer(), null);

        Span span = tracing.tracer().nextSpan().name("op").start();
        try (Tracer.SpanInScope ignored = tracing.tracer().withSpan(span)) {
            c.getStringValue("catalog.search-engine", "es", new ImmutableContext("nike"));
        } finally {
            span.end();
        }

        SpanData s = tracing.finishedSpans().get(0);
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.error.code"))).isEqualTo("FLAG_NOT_FOUND");
        assertThat(s.getAttributes().get(AttributeKey.stringKey("feature_flag.result.reason"))).isEqualTo("ERROR");
    }
}
