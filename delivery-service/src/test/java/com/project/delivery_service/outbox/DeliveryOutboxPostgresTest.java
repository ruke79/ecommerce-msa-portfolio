package com.project.delivery_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.common.outbox.partition.OutboxBacklogProbe;
import com.project.common.outbox.partition.OutboxPartitionMaintainer;

/**
 * 배송 아웃박스 — 실제 PostgreSQL(V1 ~ V5 마이그레이션)로: 롤백되면 행이 없다 · 릴레이가 순서대로 보내고 표시한다 · 브로커가 죽으면
 * 멈추고 다음 주기에 순서대로 · 영구 오류는 그 행만 FAILED · 한 파드만 돈다 · 공통 파티션 잡과 발행 지연 측정이 이 테이블을 다룬다
 * (docs/93 ③ 2단계).
 *
 * <p>[JA] 配送アウトボックスを実 PostgreSQL で: ロールバックで行がない · 順に送り印を付ける · ブローカー停止で止まり次の周期に順に ·
 *      永続エラーはその行だけ FAILED · 一つの Pod だけ · 共通のパーティションジョブと発行遅延の計測がこのテーブルを扱う。
 */
@Tag("containers")
@Testcontainers
@DisplayName("배송 아웃박스 — 실제 PostgreSQL 로 적기 · 릴레이 · 파티션")
class DeliveryOutboxPostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("deliverydb").withUsername("product").withPassword("testpw");

    static DataSource ds;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static DataSourceTransactionManager txm;

    KafkaTemplate<String, String> kafka;
    DeliveryOutbox outbox;
    DeliveryOutboxRelay relay;

    @BeforeAll
    static void migrate() {
        ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        txm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(txm);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc.update("DELETE FROM " + DeliveryOutbox.TABLE);
        kafka = mock(KafkaTemplate.class);
        outbox = new DeliveryOutbox(jdbc, Clock.systemUTC());
        relay = new DeliveryOutboxRelay(outbox, kafka, txm, 2_000);
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> fail(Throwable t) {
        return CompletableFuture.failedFuture(t);
    }

    private List<String> statuses() {
        return jdbc.queryForList("SELECT outbox_status FROM " + DeliveryOutbox.TABLE + " ORDER BY created_at, id", String.class);
    }

    private void enqueue(String key, String payload) {
        tx.executeWithoutResult(s -> outbox.enqueue("delivery-status", key, payload));
    }

    @Test
    @DisplayName("🔴 상태 변경 트랜잭션이 롤백되면 아웃박스 행도 없다 — 커밋되면 STARTED 로 남는다")
    void rowFollowsTheBusinessTransaction() {
        tx.executeWithoutResult(s -> {
            outbox.enqueue("delivery-status", "1", "{\"eventId\":\"delivery:1:DELIVERED\"}");
            s.setRollbackOnly();
        });
        assertThat(statuses()).as("롤백 — 저장되지 않은 상태의 이벤트가 나가지 않는다").isEmpty();

        enqueue("1", "{\"eventId\":\"delivery:1:DELIVERED\"}");
        assertThat(statuses()).containsExactly(DeliveryOutbox.STARTED);
    }

    @Test
    @DisplayName("🔴 릴레이가 오래된 순으로 보내고 COMPLETED 로 표시한다 — 다음 주기에는 보낼 것이 없다")
    void relaySendsInOrderAndMarksCompleted() {
        enqueue("1", "a");
        enqueue("2", "b");
        enqueue("1", "c");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());

        DeliveryOutboxRelay.Result r = relay.relayOnce();

        assertThat(r.sent()).isEqualTo(3);
        InOrder order = Mockito.inOrder(kafka);
        order.verify(kafka).send("delivery-status", "1", "a");
        order.verify(kafka).send("delivery-status", "2", "b");
        order.verify(kafka).send("delivery-status", "1", "c");
        assertThat(statuses()).containsOnly(DeliveryOutbox.COMPLETED);

        reset(kafka);
        assertThat(relay.relayOnce().sent()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 브로커가 죽으면 첫 행에서 멈춘다(행마다 기다리지 않는다) — 모두 STARTED, 다음 주기에 순서대로 나간다")
    void brokerDownStopsTheBatchAndRetriesInOrder() {
        enqueue("1", "a");
        enqueue("1", "b");
        enqueue("2", "c");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(fail(new TimeoutException("broker down")));

        DeliveryOutboxRelay.Result down = relay.relayOnce();

        assertThat(down.sent()).isZero();
        assertThat(down.deferred()).isEqualTo(3);
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        assertThat(statuses()).containsOnly(DeliveryOutbox.STARTED);
        assertThat(jdbc.queryForObject("SELECT max(attempts) FROM " + DeliveryOutbox.TABLE, Integer.class)).isEqualTo(1);

        reset(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());
        assertThat(relay.relayOnce().sent()).isEqualTo(3);
        InOrder order = Mockito.inOrder(kafka);
        order.verify(kafka).send("delivery-status", "1", "a");
        order.verify(kafka).send("delivery-status", "1", "b");
        order.verify(kafka).send("delivery-status", "2", "c");
    }

    @Test
    @DisplayName("다시 보내도 소용없는 오류(너무 큰 레코드)는 그 행만 FAILED — 뒤 행은 계속 나간다")
    void permanentErrorFailsOnlyThatRow() {
        enqueue("1", "huge");
        enqueue("2", "ok");
        when(kafka.send("delivery-status", "1", "huge")).thenReturn(fail(new RecordTooLargeException("too large")));
        when(kafka.send("delivery-status", "2", "ok")).thenReturn(ok());

        DeliveryOutboxRelay.Result r = relay.relayOnce();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.sent()).isEqualTo(1);
        assertThat(statuses()).containsExactly(DeliveryOutbox.FAILED, DeliveryOutbox.COMPLETED);
    }

    @Test
    @DisplayName("다른 파드가 릴레이 잠금을 쥐고 있으면 이번 주기는 건너뛴다 — 같은 주문의 이벤트가 엇갈려 나가지 않는다")
    void onlyOneRelayAtATime() throws Exception {
        enqueue("1", "a");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());
        try (Connection other = ds.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(hashtext('delivery-outbox-relay'))");
            }
            assertThat(relay.relayOnce().skipped()).isTrue();
            verify(kafka, never()).send(anyString(), anyString(), anyString());
            other.rollback();
        }
        assertThat(relay.relayOnce().sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("공통 파티션 잡이 이 테이블을 다룬다(앞으로 석 달) · 발행 지연 측정이 STARTED 를 센다")
    void partitionJobAndBacklogProbeHandleTheTable() {
        OutboxPartitionMaintainer maintainer = new OutboxPartitionMaintainer(ds, List.of(DeliveryOutbox.TABLE), 3, Clock.systemUTC());
        maintainer.runOnce();
        assertThat(maintainer.monthsAheadNow(DeliveryOutbox.TABLE)).as("파티션 테이블로 알아보고 앞 달을 만든다").isGreaterThanOrEqualTo(3);

        enqueue("1", "a");
        OutboxBacklogProbe probe = new OutboxBacklogProbe(ds, List.of(DeliveryOutbox.TABLE), Clock.systemUTC());
        probe.sample();
        assertThat(probe.last(DeliveryOutbox.TABLE).rows()).isEqualTo(1.0);
    }
}
