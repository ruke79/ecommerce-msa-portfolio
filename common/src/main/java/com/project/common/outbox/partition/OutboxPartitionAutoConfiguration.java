package com.project.common.outbox.partition;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스 월 파티션을 주기적으로 미리 만든다 — {@link OutboxPartitionMaintainer}.
 *
 * <p>켜는 법: 서비스 설정에 파티션 테이블을 적는다(파티션이 있는 cart · order · pay · user).
 * <pre>
 * outbox:
 *   partitions:
 *     tables: orders.payment_outbox_event
 *     months-ahead: 3        # 기본 3 — 이번 달 + 3 달
 *     interval: PT6H         # 기본 6 시간. 기동 30 초 뒤 첫 실행
 * </pre>
 *
 * <p>스프링 {@code @Scheduled} 가 아니라 자기 스레드 하나로 돈다 — 모듈마다 {@code @EnableScheduling} 여부가 달라서,
 * 켜 둔 모듈에서만 조용히 도는 일이 없게. 기동 경로를 막지도 않는다(첫 실행은 30 초 뒤).
 *
 * <p>[JA] アウトボックスの月次パーティションを定期的に前もって作る — {@link OutboxPartitionMaintainer}。
 *
 * <p>有効にする方法: サービス設定にパーティションテーブルを書く(パーティションがある cart · order · pay · user)。
 * <pre>
 * outbox:
 *   partitions:
 *     tables: orders.payment_outbox_event
 *     months-ahead: 3        # デフォルト 3 — 今月 + 3 か月
 *     interval: PT6H         # デフォルト 6 時間。起動 30 秒後に初回実行
 * </pre>
 *
 * <p>Spring の {@code @Scheduled} ではなく専用スレッド一つで回る — モジュールごとに {@code @EnableScheduling} の有無が異なるため、
 * それを有効にしたモジュールでだけ黙って回る、ということがないように。起動経路も妨げない(初回実行は 30 秒後)。
 */
@Slf4j
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "outbox.partitions", name = "tables")
public class OutboxPartitionAutoConfiguration {

    @Bean
    OutboxPartitionMaintainer outboxPartitionMaintainer(DataSource dataSource,
            @Value("${outbox.partitions.tables}") String tables,
            @Value("${outbox.partitions.months-ahead:3}") int monthsAhead) {
        List<String> list = Arrays.stream(tables.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new OutboxPartitionMaintainer(dataSource, list, monthsAhead, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "outbox.partitions", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxPartitionJob outboxPartitionJob(OutboxPartitionMaintainer maintainer,
            @Value("${outbox.partitions.interval:PT6H}") String interval,
            @Value("${outbox.partitions.initial-delay:PT30S}") String initialDelay) {
        // 문자열로 받아 직접 읽는다 — 변환 서비스가 없는 컨텍스트(테스트 러너 등)에서도 같게 동작하게.
        // [JA] 文字列で受け取って自前でパースする — 変換サービスのないコンテキスト(テストランナーなど)でも同じように動くように。
        return new OutboxPartitionJob(maintainer, Duration.parse(initialDelay), Duration.parse(interval));
    }

    /** 주기 실행. 빈이 닫히면(종료·재시작) 스레드도 멈춘다. */
    // [JA] 定期実行。Bean が閉じられると(終了・再起動)スレッドも止まる。
    static final class OutboxPartitionJob implements DisposableBean {

        private final ScheduledExecutorService executor;

        OutboxPartitionJob(OutboxPartitionMaintainer maintainer, Duration initialDelay, Duration interval) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "outbox-partition");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(maintainer::runOnce, initialDelay.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
            log.info("아웃박스 파티션 잡: tables={}, 주기={}", maintainer.tables(), interval);
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }
    }

    /**
     * 지표 — 경보가 읽는다(observability/alert-rules.yml).
     * <ul>
     *   <li>{@code outbox_partition_months_ahead{table}} — 이번 달 뒤로 이어진 미래 파티션 수. 잡이 돌면 {@code months-ahead}
     *       와 같다. 0 이면 다음 달 파티션이 없다 — 달이 바뀌면 행이 DEFAULT 로 간다</li>
     *   <li>{@code outbox_partition_default_rows{table}} — DEFAULT 파티션의 행 수. 미리 만들어 두는 한 0 이어야 한다</li>
     * </ul>
     * 한 번도 점검하지 못했으면(기동 직후 · 모든 실행 실패) 값이 NaN 이다 — 경보는 "없음" 이 아니라 "작음" 을 본다.
     *
     * <p>[JA] メトリクス — アラートが読む(observability/alert-rules.yml)。
     * <ul>
     *   <li>{@code outbox_partition_months_ahead{table}} — 今月以降に続く将来パーティションの数。ジョブが回っていれば {@code months-ahead}
     *       と等しい。0 なら来月のパーティションがない — 月が変わると行が DEFAULT に行く</li>
     *   <li>{@code outbox_partition_default_rows{table}} — DEFAULT パーティションの行数。前もって作っている限り 0 のはずである</li>
     * </ul>
     * 一度も点検できていなければ(起動直後 · 全実行失敗)値は NaN である — アラートは「ない」ではなく「小さい」を見る。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    static class Metrics {

        Metrics(MeterRegistry registry, OutboxPartitionMaintainer maintainer) {
            for (String table : maintainer.tables()) {
                Gauge.builder("outbox.partition.months.ahead", maintainer,
                        m -> m.lastStatus().containsKey(table) ? m.lastStatus().get(table).monthsCovered() : Double.NaN)
                        .tag("table", table)
                        .description("이번 달 뒤로 미리 만들어진 아웃박스 월 파티션 수")
                        .register(registry);
                Gauge.builder("outbox.partition.default.rows", maintainer,
                        m -> m.lastStatus().containsKey(table) ? m.lastStatus().get(table).defaultRows() : Double.NaN)
                        .tag("table", table)
                        .description("아웃박스 DEFAULT 파티션의 행 수 — 0 이어야 한다")
                        .register(registry);
            }
        }
    }
}
