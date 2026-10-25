package com.project.catalog_service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 실행 시간 로그 애스펙트는 기본 끔 — docs/83 §5(2026-10-23 CPU 프로파일: 모든 서비스 메서드 호출마다 INFO 한 줄, catalog CPU 7.9%), B-105.
 * <p>[JA] 実行時間ログのアスペクトは既定オフ。EXECUTION_TIME_LOG_ENABLED=true のときだけ Bean が生まれる。
 */
@DisplayName("실행 시간 로그 애스펙트 — 기본 끔, EXECUTION_TIME_LOG_ENABLED=true 로만 켠다")
class ExecutionTimeLoggerToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
            .withUserConfiguration(ExecutionTimeLogger.class);

    @Test
    @DisplayName("🔴 값이 없거나 false 면 애스펙트 빈이 없다 · true 면 있다")
    void offUnlessEnabled() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ExecutionTimeLogger.class));
        runner.withPropertyValues("EXECUTION_TIME_LOG_ENABLED=false").run(ctx -> assertThat(ctx).doesNotHaveBean(ExecutionTimeLogger.class));
        runner.withPropertyValues("EXECUTION_TIME_LOG_ENABLED=true").run(ctx -> assertThat(ctx).hasSingleBean(ExecutionTimeLogger.class));
    }
}
