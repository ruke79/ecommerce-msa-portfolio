package com.project.cart_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.project.common.outbox.partition.OutboxPartitionAutoConfiguration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 파티션 잡의 지표가 <b>실제 자동 설정 순서에서</b> 등록되는가 — 경보 셋(observability/alert-rules.yml outbox-health)의 전제.
 *
 * <p>예전 {@code @ConditionalOnBean(MeterRegistry.class)} 는 이 자동 설정이 actuator 의 메트릭 자동 설정보다 먼저(알파벳순)
 * 평가되어 늘 빠졌고, 운영에 {@code outbox_partition_*} 가 하나도 없었다. {@link OutboxPartitionWiringTest} 는 Maintainer 빈만
 * 봐서 이것을 잡지 못했다. 여기서는 메트릭 자동 설정까지 함께 띄워 Boot 의 실제 정렬로 확인한다.
 *
 * <p>[JA] パーティションジョブの指標が実際の自動設定の順序で登録されるか — アラートの前提。メトリクスの自動設定も一緒に起動し、
 * Boot の実際の並べ替えで確かめる。
 */
@DisplayName("아웃박스 파티션 지표 — 실제 자동 설정 순서에서 등록된다")
class OutboxPartitionMetricsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, OutboxPartitionAutoConfiguration.class,
                    MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:partition-metrics",
                    "outbox.partitions.tables=cart.outbox_event", "outbox.partitions.initial-delay=PT1H");

    @Test
    @DisplayName("🔴 months_ahead · default_rows · last_success 게이지가 테이블마다 있다 — 처음 값: 점검 전 NaN, 성공 시각은 기동 시각")
    void gaugesAreRegistered() {
        long before = Instant.now().getEpochSecond();
        runner.run(ctx -> {
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            Gauge ahead = registry.find("outbox.partition.months.ahead").tag("table", "cart.outbox_event").gauge();
            Gauge rows = registry.find("outbox.partition.default.rows").tag("table", "cart.outbox_event").gauge();
            Gauge success = registry.find("outbox.partition.last.success.seconds").tag("table", "cart.outbox_event").gauge();

            assertThat(ahead).as("지표가 등록되지 않았다 — 경보 셋이 울릴 수 없다").isNotNull();
            assertThat(rows).isNotNull();
            assertThat(success).isNotNull();
            assertThat(ahead.value()).as("아직 점검 전(initial-delay 1시간)").isNaN();
            assertThat(success.value()).as("처음 값은 기동 시각 — NaN 이면 '한 번도 못 돌았다' 가 경보에 잡히지 않는다")
                    .isBetween((double) before, (double) Instant.now().getEpochSecond());
        });
    }
}
