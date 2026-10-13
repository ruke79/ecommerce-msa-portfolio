package com.project.common.featureflag;

import java.util.Map;
import java.util.function.Supplier;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * 플래그 평가를 <b>지금 스팬</b>과 지표에 남긴다(docs/74) — 이 단계의 핵심이다.
 *
 * <p>플래그를 켜는 것만으로는 아무것도 알 수 없다. "켠 쪽이 느린가 · 오류가 많은가" 를 보려면 요청마다 <b>어느
 * 쪽으로 돌았는지</b>가 그 요청의 추적에 붙어 있어야 한다. 그래서:
 * <ul>
 *   <li>스팬 태그 — OTel 시맨틱 규약의 이름 그대로: {@code feature_flag.key} · {@code feature_flag.result.variant} ·
 *       {@code feature_flag.result.reason} · {@code feature_flag.provider.name}(오류면 {@code feature_flag.error.code}).
 *       Jaeger 에서 {@code feature_flag.result.variant=es} 로 찾을 수 있고, Collector 의 span_metrics 가 이 태그를
 *       차원으로 받아 <b>변형별 RED 지표</b>가 된다(observability/otel-collector.yml)</li>
 *   <li>지표 — {@code feature_flag.evaluations{key, variant, reason}}. 실제로 몇 % 가 어느 쪽으로 갔는지</li>
 * </ul>
 *
 * <p>🔴 <b>평가 문맥(대상 키 · 속성)은 남기지 않는다.</b> 대상 키는 검색어나 사용자 id 일 수 있다 — 추적도
 * 로그다(docs/63 §4). 태그 값은 정의 파일에 적힌 변형 이름뿐이라 개수에도 상한이 있다.
 *
 * <p>스팬 태그는 한 스팬에 같은 이름으로 한 번만 남는다. 한 요청에서 플래그를 둘 이상 평가하면 마지막 것이
 * 이긴다 — 그래서 {@code feature_flag.variant.<키>} 도 함께 남긴다.
 *
 * <p>[JA] フラグ評価を<b>現在のスパン</b>とメトリクスに残す(docs/74) — この段階の核心である。「有効にした側が遅いか・
 * エラーが多いか」を見るには、リクエストごとに<b>どちらで動いたか</b>がそのリクエストのトレースに付いていなければ
 * ならない。🔴 <b>評価コンテキスト(対象キー・属性)は残さない。</b> 対象キーは検索語やユーザー id でありうる —
 * トレースもログである(docs/63 §4)。
 */
@SuppressWarnings("rawtypes")
public class FeatureFlagObservabilityHook implements Hook {

    private final Supplier<Tracer> tracer;
    private final Supplier<MeterRegistry> meters;

    public FeatureFlagObservabilityHook(Supplier<Tracer> tracer, Supplier<MeterRegistry> meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    @Override
    public void finallyAfter(HookContext ctx, FlagEvaluationDetails details, Map hints) {
        String key = ctx.getFlagKey();
        String variant = details.getVariant() == null ? "none" : details.getVariant();
        String reason = details.getReason() == null ? "UNKNOWN" : details.getReason();
        String provider = ctx.getProviderMetadata() == null ? "none" : ctx.getProviderMetadata().getName();

        Tracer t = tracer.get();
        Span span = t == null ? null : t.currentSpan();
        if (span != null) {
            span.tag("feature_flag.key", key);
            span.tag("feature_flag.result.variant", variant);
            span.tag("feature_flag.result.reason", reason);
            span.tag("feature_flag.provider.name", provider);
            span.tag("feature_flag.variant." + key, variant);
            if (details.getErrorCode() != null) {
                span.tag("feature_flag.error.code", details.getErrorCode().name());
            }
        }

        MeterRegistry m = meters.get();
        if (m != null) {
            Counter.builder("feature_flag.evaluations")
                    .description("기능 플래그 평가 수 — 변형 · 이유별 (docs/74)")
                    .tag("key", key)
                    .tag("variant", variant)
                    .tag("reason", reason)
                    .register(m)
                    .increment();
        }
    }
}
