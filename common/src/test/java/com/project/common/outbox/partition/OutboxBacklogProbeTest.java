package com.project.common.outbox.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 아웃박스 발행 지연 측정기(docs/93 ③) — 실패한 테이블은 NaN(오경보 없음)이고 나머지는 계속 잰다, 미래 시각은 0.
 * 실제 SQL 은 cart {@code OutboxPartitionMetricsTest}(H2)가 본다.
 *
 * <p>[JA] アウトボックス発行遅延の計測器 — 失敗したテーブルは NaN、残りは測り続ける、未来の時刻は 0。実際の SQL は cart の試験(H2)が見る。
 */
@DisplayName("OutboxBacklogProbe — 측정 실패는 NaN, 다른 테이블은 계속")
class OutboxBacklogProbeTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /**
     * {@code table} 을 고르는 질의에 (rows, oldest) 를 돌려주는 DataSource. {@code failingTable} 을 고르면 SQLException.
     * [JA] table を選ぶクエリに (rows, oldest) を返す DataSource。failingTable を選ぶと SQLException。
     */
    private static DataSource dataSource(String failingTable, long rows, LocalDateTime oldest) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.prepareStatement(contains(failingTable))).thenThrow(new SQLException("relation does not exist"));
        PreparedStatement ok = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.prepareStatement(contains("orders.payment_outbox_event"))).thenReturn(ok);
        when(ok.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(rows);
        when(rs.getObject(2, LocalDateTime.class)).thenReturn(oldest);
        return ds;
    }

    @Test
    @DisplayName("🔴 한 테이블의 측정이 실패하면 그 테이블만 NaN — 다른 테이블은 재고, 처음 값도 NaN")
    void failureIsNaNAndOthersContinue() throws SQLException {
        LocalDateTime oldest = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(420);
        OutboxBacklogProbe probe = new OutboxBacklogProbe(dataSource("cart.outbox_event", 5, oldest),
                List.of("cart.outbox_event", "orders.payment_outbox_event"), CLOCK);

        assertThat(probe.last("orders.payment_outbox_event").oldestAgeSeconds()).as("측정 전").isNaN();
        probe.sample();

        assertThat(probe.last("cart.outbox_event").rows()).as("실패 — 0 이 아니라 NaN(못 쟀다)").isNaN();
        assertThat(probe.last("cart.outbox_event").oldestAgeSeconds()).isNaN();
        assertThat(probe.last("orders.payment_outbox_event").rows()).isEqualTo(5.0);
        assertThat(probe.last("orders.payment_outbox_event").oldestAgeSeconds()).isEqualTo(420.0);
    }

    @Test
    @DisplayName("STARTED 가 없으면 나이 0, created_at 이 시계보다 미래면 0(음수 아님)")
    void emptyOrFutureIsZero() throws SQLException {
        OutboxBacklogProbe empty = new OutboxBacklogProbe(dataSource("none", 0, null),
                List.of("orders.payment_outbox_event"), CLOCK);
        empty.sample();
        assertThat(empty.last("orders.payment_outbox_event").oldestAgeSeconds()).isZero();

        LocalDateTime future = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(90);
        OutboxBacklogProbe skew = new OutboxBacklogProbe(dataSource("none", 1, future),
                List.of("orders.payment_outbox_event"), CLOCK);
        skew.sample();
        assertThat(skew.last("orders.payment_outbox_event").oldestAgeSeconds()).isZero();
    }

    @Test
    @DisplayName("이름이 '스키마.테이블' 이 아니면 SQL 을 만들지 않고 NaN")
    void rejectsUnqualifiedName() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.prepareStatement(anyString())).thenThrow(new AssertionError("SQL 을 만들면 안 된다"));
        OutboxBacklogProbe probe = new OutboxBacklogProbe(ds, List.of("outbox_event; drop table x"), CLOCK);
        probe.sample();
        assertThat(probe.last("outbox_event; drop table x").rows()).isNaN();
    }
}
