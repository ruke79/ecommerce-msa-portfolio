package com.project.common.outbox.partition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 아웃박스 <b>발행 지연</b>을 잰다 — 아직 보내지 못한 행({@code STARTED})의 수와 가장 오래된 행의 나이 (docs/93 ③ · docs/03 부록 · docs/21 §7).
 *
 * <h3>왜</h3>
 *
 * <p>지금까지 아웃박스 경보는 <b>영구 오류</b>(행 하나가 FAILED — {@code OrderOutboxRowFailed} · {@code PayOutboxRowFailed})와
 * <b>파티션</b>만 봤다. 브로커가 느리거나 릴레이가 멈춰 행이 STARTED 로 쌓이는 동안에는 아무것도 울리지 않는다 — 결제 응답 ·
 * 재고 차감 · 장바구니 비우기 · 가입 이벤트가 늦어지는데, FAILED 가 되기 전(또는 영영 되지 않는 경우)에는 보이지 않았다.
 * 릴레이는 10초마다 돌고 커밋 직후에도 깨우므로, 정상이면 가장 오래된 STARTED 는 수십 초를 넘지 않는다.
 *
 * <h3>어떻게</h3>
 *
 * <ul>
 *   <li>테이블은 파티션 잡과 같은 {@code outbox.partitions.tables} — 아웃박스가 있는 서비스(cart · order · pay · user)는 모두 그 목록을 둔다.
 *       이름은 {@link OutboxPartitionMaintainer} 가 이미 검사했다(스키마.테이블, 소문자).</li>
 *   <li>{@link #sample()} 이 테이블마다 {@code count(*)} · {@code min(created_at)} 을 한 번에 읽어 캐시한다. 지표(게이지)는
 *       캐시만 읽는다 — 스크레이프가 DB 를 치지 않는다. 주기는 {@code outbox.backlog.interval}(기본 30초).</li>
 *   <li>나이는 {@code created_at} 과 같은 <b>UTC</b> 시계로 잰다({@code created_at} 이 UTC 로 쓰인다 — B-21). 미래 값이면 0.</li>
 *   <li>아직 한 번도 못 쟀거나 마지막 측정이 실패하면 NaN — 비교가 거짓이라 오경보가 나지 않는다. 그 대신 경보
 *       {@code OutboxBacklogMetricsMissing} 이 지표가 아예 없는 경우를, 측정 실패는 로그(테이블마다 처음 한 번)가 보인다.</li>
 *   <li>질의 시간 상한 5초 — 측정이 DB 를 오래 붙잡지 않게.</li>
 * </ul>
 *
 * <p>[JA] アウトボックスの<b>発行遅延</b>を測る — まだ送れていない行(STARTED)の数と、最も古い行の経過時間。これまでの警報は永続エラー(FAILED)と
 * パーティションだけで、ブローカーが遅い · リレーが止まって STARTED が溜まる間は何も鳴らなかった。テーブルはパーティションジョブと同じ
 * {@code outbox.partitions.tables}。{@link #sample()} がテーブルごとに count · min(created_at) を読みキャッシュし、ゲージはキャッシュだけを読む
 * (スクレイプが DB を叩かない)。経過時間は created_at と同じ UTC の時計で測る。未計測 · 失敗は NaN で誤警報を出さない。
 */
public class OutboxBacklogProbe {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogProbe.class);

    /** 측정 한 번의 결과. 측정하지 못했으면 둘 다 NaN. */
    // [JA] 一回の計測の結果。測れなかったら両方 NaN。
    public record Backlog(double rows, double oldestAgeSeconds) {
        static final Backlog UNKNOWN = new Backlog(Double.NaN, Double.NaN);
    }

    private final DataSource dataSource;
    private final List<String> tables;
    private final Clock clock;
    private final Map<String, Backlog> last = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    public OutboxBacklogProbe(DataSource dataSource, List<String> tables, Clock clock) {
        this.dataSource = dataSource;
        this.tables = List.copyOf(tables);
        this.clock = clock;
    }

    public List<String> tables() {
        return tables;
    }

    /** 게이지가 읽는 값 — 마지막 측정. 아직이면 NaN. */
    // [JA] ゲージが読む値 — 最後の計測。まだなら NaN。
    public Backlog last(String table) {
        return last.getOrDefault(table, Backlog.UNKNOWN);
    }

    /** 모든 테이블을 한 번씩 잰다. 한 테이블이 실패해도 나머지는 잰다. */
    // [JA] すべてのテーブルを一回ずつ測る。一つが失敗しても残りは測る。
    public void sample() {
        for (String table : tables) {
            try {
                last.put(table, measure(table));
                warned.remove(table);
            } catch (SQLException | RuntimeException e) {
                last.put(table, Backlog.UNKNOWN);
                if (warned.putIfAbsent(table, Boolean.TRUE) == null) {
                    log.warn("아웃박스 적체 측정 실패: table={} — {}: {}", table, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    Backlog measure(String table) throws SQLException {
        // 테이블 이름은 OutboxPartitionMaintainer 가 QUALIFIED 로 검사한 것만 온다 — 식별자라 바인드 변수로 넣을 수 없다.
        // [JA] テーブル名は OutboxPartitionMaintainer が QUALIFIED で検査したものだけ — 識別子なのでバインド変数にできない。
        if (!OutboxPartitionMaintainer.QUALIFIED.matcher(table).matches()) {
            throw new IllegalArgumentException("outbox.partitions.tables 는 '스키마.테이블'(소문자) 이어야 한다: " + table);
        }
        String sql = "SELECT count(*), min(created_at) FROM " + table + " WHERE outbox_status = 'STARTED'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long rows = rs.getLong(1);
                LocalDateTime oldest = rs.getObject(2, LocalDateTime.class);
                if (rows == 0 || oldest == null) {
                    return new Backlog(rows, 0);
                }
                long age = Duration.between(oldest, LocalDateTime.now(clock)).toSeconds();
                return new Backlog(rows, Math.max(0, age));
            }
        }
    }
}
