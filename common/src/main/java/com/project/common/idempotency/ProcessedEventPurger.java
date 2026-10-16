package com.project.common.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 컨슈머 멱등 원장({@code <스키마>.processed_event}) 정리 — 보존 기간이 지난 행을 조금씩 지운다(docs/12 §2 "보존").
 *
 * <p><b>왜.</b> 원장은 처리한 이벤트마다 한 줄을 남기는데(coupon · catalog · cart), 마이그레이션 주석이 "주기적으로 정리해도 된다" 고만
 * 적었고 지우는 곳이 없어 영구히 쌓였다. 원장이 막는 것은 <b>같은 이벤트가 다시 오는 것</b>이고, 다시 올 수 있는 기간은 정해져 있다:
 * 원래 토픽 보존(7일), 그리고 DLT(14일)에서 원래 토픽으로 다시 보내면 거기서 또 7일. 그보다 오래된 행은 아무것도 막지 않는다.
 *
 * <p><b>안전장치.</b>
 * <ul>
 *   <li>보존은 {@link #MIN_RETENTION}(22일 = DLT 14일 + 원래 토픽 7일 + 하루) 아래로 내려가지 않는다 — 설정을 잘못 적어도 다시 올 수
 *       있는 이벤트의 행은 남는다.</li>
 *   <li>한 번에 {@code batchSize} 줄씩, 문장마다 커밋한다 — 잠금 · WAL 을 짧게 둔다. 한 회차는 {@code maxBatches} 번에서 멈춘다.</li>
 *   <li>파드가 여럿이어도 같은 조건의 DELETE 라 겹쳐도 해가 없다(늦은 쪽은 0 줄).</li>
 *   <li>표 이름은 {@code <스키마>.processed_event} 모양만 받는다 — SQL 에 그대로 들어가기 때문이다.</li>
 * </ul>
 *
 * <p>기준 시각은 {@code clock} 의 지역 시간이다 — 원장의 {@code processed_at} 은 {@code LocalDateTime.now()}(JVM 기본 시간대) 또는
 * DB {@code now()}(드라이버가 세션 시간대를 JVM 기본으로 맞춘다)로 적힌다.
 *
 * <p>[JA] コンシューマ冪等台帳の掃除 — 保存期間を過ぎた行を少しずつ削除する。台帳が防ぐのは同じイベントの再到着で、その期間は決まっている
 * (元トピック 7 日、DLT 14 日から元トピックへ戻すとさらに 7 日)。保存は 22 日未満にならず、batchSize 行ずつ文ごとにコミットし、一回は
 * maxBatches 回で止まる。表名は {@code <スキーマ>.processed_event} の形だけを受け付ける(SQL にそのまま入るため)。
 */
public final class ProcessedEventPurger {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPurger.class);

    /** DLT 보존 14일(ops/02) + 원래 토픽 보존 7일 + 하루. 이보다 짧으면 DLT 에서 되돌린 이벤트가 원장을 지나 두 번 반영될 수 있다. */
    public static final Duration MIN_RETENTION = Duration.ofDays(22);

    private static final Pattern TABLE = Pattern.compile("[a-z_]+\\.processed_event");

    private final DataSource dataSource;
    private final String table;
    private final String sql;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatches;
    private final Clock clock;

    public ProcessedEventPurger(DataSource dataSource, String table, Duration retention, int batchSize, int maxBatches,
            Clock clock) {
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("원장 표 이름은 <스키마>.processed_event 모양이어야 한다: " + table);
        }
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("batchSize · maxBatches 는 1 이상: " + batchSize + ", " + maxBatches);
        }
        this.dataSource = dataSource;
        this.table = table;
        this.sql = "DELETE FROM " + table + " WHERE event_id IN (SELECT event_id FROM " + table
                + " WHERE processed_at < ? LIMIT ?)";
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            log.warn("{} 보존 {} 은 다시 올 수 있는 기간보다 짧다 — {}일로 둔다", table, retention, MIN_RETENTION.toDays());
            this.retention = MIN_RETENTION;
        } else {
            this.retention = retention;
        }
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.clock = clock;
    }

    /** 실제로 쓰는 보존 기간(하한을 적용한 뒤). */
    public Duration retention() {
        return retention;
    }

    /** @return 지운 줄 수. */
    public long purge() throws SQLException {
        Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now(clock).minus(retention));
        long total = 0;
        try (Connection c = dataSource.getConnection(); PreparedStatement st = c.prepareStatement(sql)) {
            boolean manual = !c.getAutoCommit();
            for (int i = 0; i < maxBatches; i++) {
                st.setTimestamp(1, cutoff);
                st.setInt(2, batchSize);
                int n = st.executeUpdate();
                if (manual) {
                    c.commit();
                }
                total += n;
                if (n < batchSize) {
                    log(total);
                    return total;
                }
            }
        }
        log.warn("{} 정리 — 한 회차 상한({}번 × {}줄)에 닿았다. 남은 행은 다음 회차에 지운다", table, maxBatches, batchSize);
        log(total);
        return total;
    }

    private void log(long total) {
        if (total > 0) {
            log.info("{} 정리 — {}줄 삭제(보존 {}일)", table, total, retention.toDays());
        }
    }
}
