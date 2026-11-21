package com.project.delivery_service.outbox;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 배송 아웃박스 테이블({@code delivery.outbox_event}, V5) — 보낼 이벤트를 <b>부르는 쪽의 트랜잭션 안에서</b> 적고, 릴레이가 읽고 표시한다.
 *
 * <p>JdbcTemplate 이라 JPA 트랜잭션(JpaTransactionManager 가 같은 커넥션을 묶는다)에 그대로 들어간다 — 상태 변경이 롤백되면 이 행도 없다.
 * {@code created_at} 은 UTC(B-21 — 파티션 경계 · 발행 지연 측정이 UTC 다).
 *
 * <p>[JA] 配送アウトボックス — 送るイベントを呼び出し側のトランザクション内で書き、リレーが読んで印を付ける。JdbcTemplate なので JPA の
 *      トランザクションにそのまま入る(状態変更がロールバックされればこの行もない)。created_at は UTC。
 */
@Component
public class DeliveryOutbox {

    public static final String TABLE = "delivery.outbox_event";
    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    /** 보낼 행 하나. 파티션 테이블이라 키는 (id, created_at). */
    // [JA] 送る行一つ。パーティションテーブルなのでキーは (id, created_at)。
    public record Row(String id, LocalDateTime createdAt, String topic, String key, String payload, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public DeliveryOutbox(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DeliveryOutbox(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 이벤트 하나를 적는다 — 부르는 쪽의 트랜잭션에 든다. */
    // [JA] イベントを一つ書く — 呼び出し側のトランザクションに入る。
    public void enqueue(String topic, String key, String payload) {
        jdbc.update("INSERT INTO " + TABLE + " (id, topic, msg_key, payload, outbox_status, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, '" + STARTED + "', 0, ?)",
                UUID.randomUUID().toString(), topic, key, payload, LocalDateTime.now(clock));
    }

    /**
     * 릴레이를 한 파드만 돌게 하는 트랜잭션 잠금 — 같은 주문의 이벤트가 두 파드에서 엇갈려 나가지 않게(순서). 트랜잭션이 끝나면 풀린다.
     * [JA] リレーを一つの Pod だけで回すトランザクションロック — 同じ注文のイベントが二つの Pod で前後しないように。
     */
    public boolean tryRelayLock() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext('delivery-outbox-relay'))", Boolean.class);
        return Boolean.TRUE.equals(locked);
    }

    /** 아직 보내지 못한 행을 오래된 순으로. */
    // [JA] まだ送れていない行を古い順に。
    public List<Row> pending(int limit) {
        return jdbc.query("SELECT id, created_at, topic, msg_key, payload, attempts FROM " + TABLE
                + " WHERE outbox_status = '" + STARTED + "' ORDER BY created_at, id LIMIT ?",
                (rs, i) -> new Row(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6)),
                limit);
    }

    public void markCompleted(Row row) {
        jdbc.update("UPDATE " + TABLE + " SET outbox_status = '" + COMPLETED + "', sent_at = ?, attempts = attempts + 1"
                + " WHERE id = ? AND created_at = ?", LocalDateTime.now(clock), row.id(), row.createdAt());
    }

    /** 다시 보내도 소용없는 행(너무 큼 · 직렬화 · 토픽 없음) — 그 행만 멈춘다. */
    // [JA] 送り直しても無駄な行 — その行だけ止める。
    public void markFailed(Row row) {
        jdbc.update("UPDATE " + TABLE + " SET outbox_status = '" + FAILED + "', attempts = attempts + 1"
                + " WHERE id = ? AND created_at = ?", row.id(), row.createdAt());
    }

    /** 일시 실패 — STARTED 그대로, 시도 수만 올린다(다음 주기에 다시). */
    // [JA] 一時的な失敗 — STARTED のまま、試行回数だけ上げる(次の周期に再度)。
    public void markRetry(Row row) {
        jdbc.update("UPDATE " + TABLE + " SET attempts = attempts + 1 WHERE id = ? AND created_at = ?", row.id(), row.createdAt());
    }
}
