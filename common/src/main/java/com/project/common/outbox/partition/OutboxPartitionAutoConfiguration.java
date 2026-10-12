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
import io.micrometer.core.instrument.binder.MeterBinder;
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
 *     interval: PT6H         # 기본 6 시간. 기동 30 초 뒤 첫 실행 — ISO-8601 기간만(6h 는 기동 실패)
 *     enabled: false         # 잡을 끄는 키는 이것이다(tables 에 false 를 쓰지 않는다). 지표는 남아 하루 뒤
 *                            # last_success 경보가 울린다 — 파티션을 아무도 만들지 않는다는 뜻이므로 의도된 것
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
 *     interval: PT6H         # デフォルト 6 時間。起動 30 秒後に初回実行 — ISO-8601 の期間のみ(6h は起動失敗)
 *     enabled: false         # ジョブを止めるキーはこれ(tables に false を書かない)。指標は残り、1 日後に
 *                            # last_success のアラートが鳴る — 誰もパーティションを作らないという意味なので意図どおり
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
        return new OutboxPartitionJob(maintainer::runOnce, String.valueOf(maintainer.tables()),
                Duration.parse(initialDelay), Duration.parse(interval));
    }

    /** 주기 실행. 빈이 닫히면(종료·재시작) 스레드도 멈춘다. */
    // [JA] 定期実行。Bean が閉じられると(終了・再起動)スレッドも止まる。
    static final class OutboxPartitionJob implements DisposableBean {

        private final ScheduledExecutorService executor;

        OutboxPartitionJob(Runnable task, String tables, Duration initialDelay, Duration interval) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "outbox-partition");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(() -> runSafely(task), initialDelay.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
            log.info("아웃박스 파티션 잡: tables={}, 주기={}", tables, interval);
        }

        /**
         * 무엇이 던져져도 다음 주기는 돈다. {@code scheduleWithFixedDelay} 는 작업이 한 번이라도 예외를 던지면 그 뒤 실행을
         * <b>조용히 취소</b>한다 — {@code runOnce} 는 SQLException · RuntimeException 만 잡으므로 {@code Error}
         * (NoClassDefFoundError · 일시적인 OOM 등) 한 번에 잡이 영구히 멈췄다. 여기서 막고 남기면, 반복되는 실패는
         * {@code outbox_partition_last_success_seconds} 경보가 드러낸다.
         * [JA] 何が投げられても次の周期は回る — 一度でも例外が漏れると以後の実行が黙って取り消されるため。
         */
        static void runSafely(Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("아웃박스 파티션 잡이 예상하지 못한 오류로 끝났다 — 다음 주기에 다시 한다", t);
            }
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }
    }

    /**
     * 지표 — 경보가 읽는다(observability/alert-rules.yml).
     * <ul>
     *   <li>{@code outbox_partition_months_ahead{table}} — <b>지금</b> 기준 이번 달 뒤로 이어진 미래 파티션 수. 잡이 돌면
     *       {@code months-ahead} 와 같고, 잡이 멈추면 달이 바뀔 때마다 1 씩 준다({@link OutboxPartitionMaintainer#monthsAheadNow}).
     *       0 이면 다음 달 파티션이 없다 — 달이 바뀌면 행이 DEFAULT 로 간다</li>
     *   <li>{@code outbox_partition_default_rows{table}} — DEFAULT 파티션의 행 수(마지막 성공 점검). 0 이어야 한다</li>
     *   <li>{@code outbox_partition_last_success_seconds{table}} — 마지막 성공 시각. 처음 값은 기동 시각이라, 기동 뒤로 한 번도
     *       못 돌아도 경보가 잡는다</li>
     * </ul>
     *
     * <p>⚠️ {@code MeterBinder} 빈으로 등록한다 — Boot 가 레지스트리를 만든 뒤 순서와 상관없이 바인딩한다. 예전에는
     * {@code @ConditionalOnBean(MeterRegistry.class)} 였는데, 이 자동 설정의 순서 지정은 DataSource 뒤 하나뿐이라 알파벳순으로
     * {@code com.project…} 가 actuator 의 메트릭 자동 설정보다 먼저 평가됐다. 그 순간에는 레지스트리가 없어 이 설정 전체가 건너뛰어졌고,
     * 운영에서 {@code outbox_partition_*} 시계열이 아예 없어 경보 셋이 울릴 수 없었다({@code OutboxPartitionMetricsTest}).
     *
     * <p>[JA] メトリクス — アラートが読む。{@code MeterBinder} Bean で登録する — Boot がレジストリを作った後に順序に関係なく
     * バインドする。以前の {@code @ConditionalOnBean(MeterRegistry.class)} は、この自動設定がアクチュエータのメトリクス自動設定より
     * 先に評価されるため常に外れ、本番に指標が一つも出ていなかった。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class Metrics {

        @Bean
        MeterBinder outboxPartitionMetrics(OutboxPartitionMaintainer maintainer) {
            return registry -> {
                for (String table : maintainer.tables()) {
                    Gauge.builder("outbox.partition.months.ahead", maintainer, m -> m.monthsAheadNow(table))
                            .tag("table", table)
                            .description("지금 기준 이번 달 뒤로 미리 만들어진 아웃박스 월 파티션 수")
                            .register(registry);
                    Gauge.builder("outbox.partition.default.rows", maintainer, m -> m.defaultRows(table))
                            .tag("table", table)
                            .description("아웃박스 DEFAULT 파티션의 행 수 — 0 이어야 한다")
                            .register(registry);
                    Gauge.builder("outbox.partition.last.success.seconds", maintainer, m -> m.lastSuccessEpochSeconds(table))
                            .tag("table", table)
                            .description("아웃박스 파티션 점검이 마지막으로 성공한 시각(유닉스 초) — 처음 값은 기동 시각")
                            .register(registry);
                }
            };
        }
    }
}
