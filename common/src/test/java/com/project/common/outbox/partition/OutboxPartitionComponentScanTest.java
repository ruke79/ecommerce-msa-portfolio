package com.project.common.outbox.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * cart · order · chat 처럼 {@code com.project.common} 을 <b>컴포넌트 스캔</b>하는 앱에서 파티션 자동 설정이 제대로 뜨는가.
 *
 * <p>🐛 세 앱은 {@code @ComponentScan} 을 직접 달아 {@code @SpringBootApplication} 이 기본으로 거는 {@code AutoConfigurationExcludeFilter}
 * 가 빠져 있었다. 그러면 자동 설정 클래스가 <b>사용자 설정처럼 스캔</b>되고, {@code @ConditionalOnBean(DataSource.class)} 가 DataSource
 * 자동 설정보다 먼저 평가돼 이 자동 설정이 통째로 빠졌다 — cart · order 의 파티션 잡은 한 번도 돌지 않았다(V1 이 만든 2027-01 까지만
 * 있다). 게다가 안쪽 {@code Metrics} 는 {@code @Configuration} 이라 따로 스캔돼, 잡 없이 지표만 Maintainer 를 찾다 <b>기동이 실패</b>했다
 * (chat 처럼 아웃박스 표가 없는 앱도). {@code ApplicationContextRunner} 의 자동 설정 테스트는 스캔을 하지 않아 이것을 못 잡았다.
 *
 * <p>[JA] {@code com.project.common} をコンポーネントスキャンするアプリでパーティション自動設定が正しく起動するか。三つのアプリは
 * {@code AutoConfigurationExcludeFilter} が抜けており、自動設定クラスがユーザー設定としてスキャンされ、DataSource より先に条件が評価されて
 * 丸ごと外れていた(cart・order のジョブは一度も動いていない)。内側の {@code Metrics} は別にスキャンされ起動が失敗していた。
 */
@DisplayName("파티션 자동 설정 — common 을 스캔하는 앱(cart · order · chat)에서")
class OutboxPartitionComponentScanTest {

    /** 세 앱이 지금 다는 스캔 — TypeExcludeFilter + AutoConfigurationExcludeFilter. 테스트 클래스(이 파일)는 스캔에서 뺀다. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = OutboxPartitionAutoConfiguration.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*") })
    static class AppScan {
    }

    /** 예전 스캔 — TypeExcludeFilter 만. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = OutboxPartitionAutoConfiguration.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*") })
    static class OldAppScan {
    }

    /**
     * 앱처럼 DataSource 를 <b>자동 설정</b>이 만든다(사용자 빈이 아니다 — 그래야 스캔된 자동 설정의 조건이 DataSource 보다 먼저
     * 평가되는 실제 순서가 재현된다). 잡은 1시간 뒤에야 DB 에 붙으므로 가짜로 충분하다.
     */
    @AutoConfiguration(before = OutboxPartitionAutoConfiguration.class)
    static class FakeDataSourceAutoConfiguration {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FakeDataSourceAutoConfiguration.class, OutboxPartitionAutoConfiguration.class))
            .withPropertyValues("outbox.partitions.initial-delay=PT1H");

    @Test
    @DisplayName("🔴 아웃박스 표가 있는 앱(cart · order): 잡(Maintainer)과 지표가 뜬다")
    void appWithTablesGetsTheJob() {
        runner.withUserConfiguration(AppScan.class).withPropertyValues("outbox.partitions.tables=cart.outbox_event")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(OutboxPartitionMaintainer.class);
                    assertThat(ctx).hasSingleBean(MeterBinder.class);
                });
    }

    @Test
    @DisplayName("🔴 아웃박스 표가 없는 앱(chat): 기동이 실패하지 않는다 — 지표도 잡도 없다")
    void appWithoutTablesStillStarts() {
        runner.withUserConfiguration(AppScan.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(OutboxPartitionMaintainer.class);
            assertThat(ctx).doesNotHaveBean(MeterBinder.class);
        });
    }

    /**
     * 🔴 실제 앱 — {@code com.project.common} 을 스캔하는 {@code @ComponentScan} 은 AutoConfigurationExcludeFilter 를 함께 단다. 앱 기동
     * 테스트(OrderServiceApplicationTests 등)는 컨테이너가 있어야 돌아, 단위 테스트 단계에서도 여기서 막는다.
     */
    @Test
    @DisplayName("🔴 common 을 스캔하는 앱은 모두 AutoConfigurationExcludeFilter 를 단다")
    void appsThatScanCommonExcludeAutoConfigurations() throws java.io.IOException {
        java.nio.file.Path cwd = java.nio.file.Paths.get("").toAbsolutePath();
        java.nio.file.Path root = cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
        java.util.List<String> scanning = new java.util.ArrayList<>();
        java.util.List<String> missing = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root, 8)) {
            for (java.nio.file.Path f : files.filter(f -> f.toString().endsWith("Application.java")
                    && f.toString().contains("/src/main/java/")).toList()) {
                String src = java.nio.file.Files.readString(f);
                if (src.contains("@ComponentScan(") && src.contains("\"com.project.common\"")) {
                    scanning.add(f.getFileName().toString());
                    if (!src.contains("AutoConfigurationExcludeFilter.class")) {
                        missing.add(root.relativize(f).toString());
                    }
                }
            }
        }
        assertThat(scanning).as("common 을 스캔하는 앱을 찾지 못했다 — 경로가 바뀌었으면 이 테스트를 고친다")
                .contains("OrderServiceApplication.java", "CartServiceApplication.java", "ChatServerApplication.java");
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("대조: AutoConfigurationExcludeFilter 가 없는 스캔에서는 자동 설정이 통째로 빠진다 — 세 앱에 필터가 필요한 이유")
    void withoutTheFilterTheJobIsLost() {
        runner.withUserConfiguration(OldAppScan.class).withPropertyValues("outbox.partitions.tables=cart.outbox_event")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(OutboxPartitionMaintainer.class);
                });
    }
}
