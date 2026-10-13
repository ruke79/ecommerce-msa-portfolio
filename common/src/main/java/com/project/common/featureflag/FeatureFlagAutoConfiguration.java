package com.project.common.featureflag;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;

/**
 * 기능 플래그(docs/74). 서비스 코드는 OpenFeature {@link Client} 를 받아 평가한다.
 *
 * <ul>
 *   <li>{@code feature-flags.file} — 정의 파일 경로(k8s: {@code FEATURE_FLAGS_FILE=/app/flags/flags.json}).
 *       비우면 공급자 없이 뜬다 — 모든 평가가 <b>코드의 기본값</b>이다(로컬 bootRun · docker compose)</li>
 *   <li>{@code feature-flags.reload-interval} — 파일을 다시 확인하는 최소 간격. 기본 5초</li>
 * </ul>
 *
 * <p>전역 {@code OpenFeatureAPI.getInstance()} 가 아니라 <b>격리된 인스턴스</b>를 쓴다. 전역이면 같은 JVM 의 테스트
 * 컨텍스트들이 서로의 공급자를 덮어쓴다.
 *
 * <p>[JA] 機能フラグ(docs/74)。サービスコードは OpenFeature {@link Client} を受け取って評価する。
 * {@code feature-flags.file} を空にするとプロバイダーなしで起動する — すべての評価が<b>コードのデフォルト値</b>になる。
 * グローバルな {@code OpenFeatureAPI.getInstance()} ではなく<b>隔離されたインスタンス</b>を使う。グローバルだと
 * 同じ JVM 内のテストコンテキストが互いのプロバイダーを上書きしてしまう。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = { "io.micrometer.core.instrument.MeterRegistry", "io.micrometer.tracing.Tracer" })
public class FeatureFlagAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public OpenFeatureAPI featureFlagApi(
            @Value("${feature-flags.file:}") String file,
            @Value("${feature-flags.reload-interval:5s}") String reloadInterval,
            ObjectProvider<Tracer> tracer,
            ObjectProvider<MeterRegistry> meters) {
        OpenFeatureAPI api = OpenFeatureAPI.createIsolated();
        api.addHooks(new FeatureFlagObservabilityHook(tracer::getIfAvailable, meters::getIfAvailable));
        if (file == null || file.isBlank()) {
            log.info("feature-flags.file 이 비었다 — 기능 플래그는 모두 코드의 기본값으로 돈다");
        } else {
            // "5s" · "PT5S" · "5000"(ms) — Boot 의 변환 서비스가 없는 문맥(테스트 러너 등)에서도 같게 읽는다.
            Duration interval = org.springframework.boot.convert.DurationStyle.detectAndParse(reloadInterval);
            api.setProviderAndWait(new FileFlagProvider(Path.of(file), interval));
        }
        return api;
    }

    @Bean
    @ConditionalOnMissingBean
    public Client featureFlagClient(OpenFeatureAPI api,
            @Value("${spring.application.name:app}") String application) {
        return api.getClient(application);
    }
}
