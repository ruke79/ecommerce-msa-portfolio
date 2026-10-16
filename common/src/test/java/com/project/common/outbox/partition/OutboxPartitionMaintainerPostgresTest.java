package com.project.common.outbox.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 파티션 잡을 <b>실제 PostgreSQL</b> 에서 돌린다 — 파티션 DDL 은 H2 로 흉내 낼 수 없다.
 *
 * <p>테이블은 V1__partition_outbox_event.sql 과 같은 모양이다(부모 + 2026-07 ~ 2027-01 + DEFAULT, PK 에 created_at,
 * 부분 인덱스, 나중에 더한 컬럼 trace_context). DB 는 {@code OUTBOX_PG_URL} 이 있으면 그것(로컬 PostgreSQL), 없으면
 * Testcontainers 로 띄운다. {@code containers} 태그라 {@code -PwithContainers} 에서만 돈다(CI 통합 단계).
 */
@Tag("containers")
@DisplayName("아웃박스 파티션 잡 — 실제 PostgreSQL")
class OutboxPartitionMaintainerPostgresTest {

    private static PostgreSQLContainer<?> container;
    private static DataSource ds;

    @BeforeAll
    static void start() {
        String url = System.getenv("OUTBOX_PG_URL");
        PGSimpleDataSource d = new PGSimpleDataSource();
        if (url != null && !url.isBlank()) {
            d.setUrl(url);
        } else {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
            container.start();
            d.setUrl(container.getJdbcUrl());
            d.setUser(container.getUsername());
            d.setPassword(container.getPassword());
        }
        ds = d;
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void schema() throws SQLException {
        exec("DROP SCHEMA IF EXISTS orders CASCADE",
                "CREATE SCHEMA orders",
                "CREATE TABLE orders.payment_outbox_event (id varchar(255) NOT NULL, aggregate_id varchar(255) NOT NULL,"
                        + " aggregate_type varchar(255) NOT NULL, event_type varchar(255) NOT NULL, payload jsonb NOT NULL,"
                        + " outbox_status varchar(255) CHECK (outbox_status IN ('STARTED','COMPLETED','FAILED')),"
                        + " created_at timestamp(6) NOT NULL, PRIMARY KEY (id, created_at)) PARTITION BY RANGE (created_at)",
                "CREATE TABLE orders.payment_outbox_event_2027_01 PARTITION OF orders.payment_outbox_event"
                        + " FOR VALUES FROM ('2027-01-01') TO ('2027-02-01')",
                "CREATE TABLE orders.payment_outbox_event_default PARTITION OF orders.payment_outbox_event DEFAULT",
                "CREATE INDEX idx_payment_outbox_started ON orders.payment_outbox_event (created_at) WHERE outbox_status = 'STARTED'",
                // V14 처럼 나중에 더한 컬럼 — 컬럼 순서가 LIKE 와 어긋나지 않는지 본다
                "ALTER TABLE orders.payment_outbox_event ADD COLUMN trace_context varchar(512)");
    }

    private static OutboxPartitionMaintainer at(String instant, int monthsAhead) {
        return new OutboxPartitionMaintainer(ds, List.of("orders.payment_outbox_event"), monthsAhead,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("🔴 V1 이 만든 마지막 달(2027-01) 뒤를 만든다 — 없으면 2027-02 부터 모든 행이 DEFAULT 로 간다")
    void createsMonthsAhead() throws SQLException {
        OutboxPartitionMaintainer.Status s = at("2027-01-15T00:00:00Z", 3).ensure("orders.payment_outbox_event");

        assertThat(s.created()).containsExactly("orders.payment_outbox_event_2027_02",
                "orders.payment_outbox_event_2027_03", "orders.payment_outbox_event_2027_04");
        assertThat(s.monthsCovered()).isEqualTo(3);
        assertThat(partitions()).contains("payment_outbox_event_2027_02", "payment_outbox_event_2027_04");

        // 새 행은 새 파티션으로 간다(DEFAULT 가 아니라)
        insert("a", "2027-03-10 12:00:00");
        assertThat(where("a")).isEqualTo("payment_outbox_event_2027_03");
        // 부모의 부분 인덱스가 새 파티션에도 생겼다
        assertThat(query("SELECT count(*) FROM pg_indexes WHERE schemaname = 'orders'"
                + " AND tablename = 'payment_outbox_event_2027_03' AND indexdef LIKE '%STARTED%'")).isEqualTo("1");
    }

    @Test
    @DisplayName("🔴 DEFAULT 에 그 달의 행이 이미 있어도 만든다 — 행을 옮기고 붙인다(그냥 PARTITION OF 면 실패)")
    void movesRowsOutOfDefault() throws SQLException {
        // 잡이 멈춰 있던 동안 2027-02 행이 DEFAULT 에 쌓였다
        insert("late-1", "2027-02-03 09:00:00");
        insert("late-2", "2027-02-27 23:59:59");
        insert("older", "2026-05-01 00:00:00");   // 이관분 — 범위 밖이라 DEFAULT 에 남는다

        OutboxPartitionMaintainer.Status s = at("2027-02-10T00:00:00Z", 1).ensure("orders.payment_outbox_event");

        assertThat(s.created()).containsExactly("orders.payment_outbox_event_2027_02",
                "orders.payment_outbox_event_2027_03");
        assertThat(where("late-1")).isEqualTo("payment_outbox_event_2027_02");
        assertThat(where("late-2")).isEqualTo("payment_outbox_event_2027_02");
        assertThat(where("older")).isEqualTo("payment_outbox_event_default");
        assertThat(s.defaultRows()).isEqualTo(1);
        // 옮긴 행의 값이 그대로다(나중에 더한 trace_context 포함 — 컬럼을 이름으로 옮긴다)
        assertThat(query("SELECT trace_context FROM orders.payment_outbox_event WHERE id = 'late-1'")).isEqualTo("tc");
    }

    @Test
    @DisplayName("🔴 DETACH 된 채 남은 같은 이름의 테이블은 '그 달이 있다' 가 아니다 — 지표를 속이지 않고 실패한다")
    void detachedLeftoverIsNotAPartition() throws SQLException {
        // 보존 작업(DETACH → DROP)이 두 단계 사이에서 멈춘 모양
        exec("CREATE TABLE orders.payment_outbox_event_2027_02 PARTITION OF orders.payment_outbox_event"
                        + " FOR VALUES FROM ('2027-02-01') TO ('2027-03-01')",
                "ALTER TABLE orders.payment_outbox_event DETACH PARTITION orders.payment_outbox_event_2027_02");

        OutboxPartitionMaintainer m = at("2027-01-15T00:00:00Z", 3);
        // 예전: 이름만 보고 건너뛰어 monthsCovered=3 을 보고했고, 2027-02 행은 모두 DEFAULT 로 갔다
        assertThatThrownBy(() -> m.ensure("orders.payment_outbox_event"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders.payment_outbox_event_2027_02")
                .hasMessageContaining("파티션이 아니다");
        assertThat(partitions()).as("한 트랜잭션 — 실패하면 다른 달도 만들지 않는다")
                .doesNotContain("payment_outbox_event_2027_03");
        assertThat(m.monthsAheadNow("orders.payment_outbox_event")).as("성공으로 기록하지 않는다").isNaN();

        // 사람이 남은 테이블을 정리하면 다음 주기에 채운다
        exec("DROP TABLE orders.payment_outbox_event_2027_02");
        OutboxPartitionMaintainer.Status s = m.ensure("orders.payment_outbox_event");
        assertThat(s.created()).contains("orders.payment_outbox_event_2027_02");
        assertThat(s.monthsCovered()).isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 같은 이름의 인덱스가 있어도 '그 달이 있다' 가 아니다")
    void sameNamedIndexIsNotAPartition() throws SQLException {
        exec("CREATE INDEX payment_outbox_event_2027_02 ON orders.payment_outbox_event_2027_01 (created_at)");

        assertThatThrownBy(() -> at("2027-01-15T00:00:00Z", 3).ensure("orders.payment_outbox_event"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders.payment_outbox_event_2027_02");
    }

    @Test
    @DisplayName("🔴 백업처럼 DEFAULT 를 오래 쥔 쪽이 있으면 기다리지 않고 실패한다 — 그 뒤 INSERT 를 막지 않는다(lock_timeout)")
    void doesNotQueueBehindALongLock() throws Exception {
        try (Connection dump = ds.getConnection()) {
            dump.setAutoCommit(false);
            try (Statement st = dump.createStatement()) {
                // pg_dump 가 테이블마다 잡는 것과 같은 잠금
                st.execute("LOCK TABLE orders.payment_outbox_event_default IN ACCESS SHARE MODE");
            }
            OutboxPartitionMaintainer m = at("2027-01-15T00:00:00Z", 1);
            long start = System.nanoTime();
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> m.ensure("orders.payment_outbox_event")),
                    "ATTACH 가 잠금 줄에서 끝없이 기다린다 — lock_timeout 이 없다");
            assertThat(thrown).isInstanceOf(SQLException.class);
            assertThat(((SQLException) thrown).getSQLState()).as("lock_not_available").isEqualTo("55P03");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
            assertThat(partitions()).as("롤백 — 붙다 만 달이 없다").doesNotContain("payment_outbox_event_2027_02");
            dump.rollback();
        }
        // 잠금이 풀리면 다음 주기에 만든다
        assertThat(at("2027-01-15T00:00:00Z", 1).ensure("orders.payment_outbox_event").created())
                .containsExactly("orders.payment_outbox_event_2027_02");
    }

    @Test
    @DisplayName("두 번 돌려도 같다 — 이미 있으면 아무것도 하지 않는다")
    void idempotent() throws SQLException {
        at("2027-01-15T00:00:00Z", 2).ensure("orders.payment_outbox_event");
        OutboxPartitionMaintainer.Status again = at("2027-01-15T00:00:00Z", 2).ensure("orders.payment_outbox_event");

        assertThat(again.created()).isEmpty();
        assertThat(again.monthsCovered()).isEqualTo(2);
    }

    @Test
    @DisplayName("레플리카 둘이 동시에 돌아도 한 번만 만든다 — advisory lock")
    void concurrentReplicas() throws Exception {
        OutboxPartitionMaintainer a = at("2027-01-15T00:00:00Z", 3);
        OutboxPartitionMaintainer b = at("2027-01-15T00:00:00Z", 3);
        List<Throwable> errors = new ArrayList<>();
        List<List<String>> created = new ArrayList<>();
        Thread t1 = new Thread(() -> run(a, created, errors));
        Thread t2 = new Thread(() -> run(b, created, errors));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(errors).isEmpty();
        assertThat(created.stream().mapToInt(List::size).sum()).isEqualTo(3);
    }

    @Test
    @DisplayName("파티션 테이블이 아니면 건너뛴다")
    void skipsPlainTable() throws SQLException {
        exec("CREATE TABLE orders.plain (id int)");
        assertThat(new OutboxPartitionMaintainer(ds, List.of("orders.plain"), 3, Clock.systemUTC())
                .ensure("orders.plain")).isNull();
    }

    private static void run(OutboxPartitionMaintainer m, List<List<String>> created, List<Throwable> errors) {
        try {
            List<String> c = m.ensure("orders.payment_outbox_event").created();
            synchronized (created) {
                created.add(c);
            }
        } catch (Throwable e) {
            synchronized (errors) {
                errors.add(e);
            }
        }
    }

    private static void insert(String id, String createdAt) throws SQLException {
        exec("INSERT INTO orders.payment_outbox_event (id, aggregate_id, aggregate_type, event_type, payload,"
                + " outbox_status, created_at, trace_context) VALUES ('" + id + "', 'agg', 'ORDER', 'E', '{}',"
                + " 'STARTED', '" + createdAt + "', 'tc')");
    }

    private static String where(String id) throws SQLException {
        return query("SELECT tableoid::regclass::text FROM orders.payment_outbox_event WHERE id = '" + id + "'")
                .replace("orders.", "");
    }

    private static List<String> partitions() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT inhrelid::regclass::text FROM pg_inherits"
                        + " WHERE inhparent = 'orders.payment_outbox_event'::regclass")) {
            while (rs.next()) {
                out.add(rs.getString(1).replace("orders.", ""));
            }
        }
        return out;
    }

    private static String query(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(String... sqls) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        }
    }
}
