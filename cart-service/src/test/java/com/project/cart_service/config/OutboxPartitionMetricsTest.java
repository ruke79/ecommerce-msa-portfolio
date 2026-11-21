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
                    "outbox.partitions.tables=cart.outbox_event", "outbox.partitions.initial-delay=PT1H",
                    "outbox.backlog.initial-delay=PT1H");

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

    /**
     * 발행 지연(docs/93 ③) — 경보 {@code OutboxPublishLagging} 이 읽는 두 게이지. 측정 전에는 NaN(오경보 없음), 측정하면 STARTED 만 센다.
     * [JA] 発行遅延 — 警報 OutboxPublishLagging が読む二つのゲージ。計測前は NaN、計測すると STARTED だけを数える。
     */
    @Test
    @DisplayName("🔴 outbox_pending_rows · oldest_age 게이지 — 측정 전 NaN, 측정하면 STARTED 만 세고 가장 오래된 나이")
    void backlogGaugesMeasureStartedRows() {
        runner.run(ctx -> {
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            Gauge rows = registry.find("outbox.pending.rows").tag("table", "cart.outbox_event").gauge();
            Gauge age = registry.find("outbox.pending.oldest.age.seconds").tag("table", "cart.outbox_event").gauge();
            assertThat(rows).as("지표가 등록되지 않았다 — 발행 지연 경보가 울릴 수 없다").isNotNull();
            assertThat(age).isNotNull();
            assertThat(age.value()).as("아직 측정 전(initial-delay 1시간) — 0 이 아니라 NaN").isNaN();

            javax.sql.DataSource ds = ctx.getBean(javax.sql.DataSource.class);
            java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
            try (var c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS cart");
                st.execute("CREATE TABLE cart.outbox_event (id bigint, outbox_status varchar(255), created_at timestamp(6))");
            }
            try (var c = ds.getConnection(); var ps = c.prepareStatement(
                    "INSERT INTO cart.outbox_event VALUES (?, ?, ?)")) {
                Object[][] data = {
                        {1L, "STARTED", now.minusSeconds(400)},
                        {2L, "STARTED", now.minusSeconds(30)},
                        {3L, "COMPLETED", now.minusDays(2)},   // 끝난 행 — 세지 않는다 [JA] 終わった行 — 数えない
                        {4L, "FAILED", now.minusDays(1)},      // 영구 오류 — 따로(OrderOutboxRowFailed 류) [JA] 永続エラー — 別に(OrderOutboxRowFailed の類)
                };
                for (Object[] r : data) {
                    ps.setLong(1, (Long) r[0]);
                    ps.setString(2, (String) r[1]);
                    ps.setObject(3, r[2]);
                    ps.executeUpdate();
                }
            }
            ctx.getBean(com.project.common.outbox.partition.OutboxBacklogProbe.class).sample();

            assertThat(rows.value()).as("STARTED 둘만").isEqualTo(2.0);
            assertThat(age.value()).as("가장 오래된 STARTED(400초 전)의 나이 — COMPLETED · FAILED 는 보지 않는다")
                    .isBetween(395.0, 460.0);
        });
    }
}
