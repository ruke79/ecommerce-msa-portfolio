package com.project.common.outbox.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("아웃박스 파티션 잡 — 설정 검증 · 지표가 잡의 멈춤을 드러내는가")
class OutboxPartitionMaintainerTest {

    @Test
    @DisplayName("파티션 이름은 V1 마이그레이션과 같은 <테이블>_YYYY_MM")
    void partitionName() {
        assertThat(OutboxPartitionMaintainer.partitionName("payment_outbox_event", YearMonth.of(2027, 2)))
                .isEqualTo("payment_outbox_event_2027_02");
    }

    @Test
    @DisplayName("🔴 테이블 이름은 '스키마.테이블' 소문자만 — 식별자를 SQL 에 이어 붙이므로")
    void rejectsUnsafeIdentifiers() {
        for (String bad : List.of("payment_outbox_event", "orders.x; DROP TABLE y", "Orders.Outbox", "orders.\"x\"")) {
            assertThatThrownBy(() -> new OutboxPartitionMaintainer(null, List.of(bad), 3, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new OutboxPartitionMaintainer(null, List.of("orders.t"), -1, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 테스트가 시각을 옮길 수 있는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void set(String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DataSource downDatabase() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        return ds;
    }

    @Test
    @DisplayName("🔴 남은 달은 지금 기준으로 계산한다 — 잡이 멈추면 달이 바뀔 때마다 1 씩 줄어 경보 문턱(<2 · <1)에 닿는다")
    void monthsAheadShrinksWhileTheJobIsStopped() throws Exception {
        MutableClock clock = new MutableClock("2026-10-12T00:00:00Z");
        OutboxPartitionMaintainer m = new OutboxPartitionMaintainer(downDatabase(), List.of("orders.t"), 3, clock);
        assertThat(m.monthsAheadNow("orders.t")).as("한 번도 점검하지 못했다").isNaN();

        // 2026-10 에 성공 — 2027-01 까지 이어져 있다(이번 달 + 3).
        m.record("orders.t", new OutboxPartitionMaintainer.Status(3, YearMonth.of(2027, 1), 0, List.of()));
        assertThat(m.monthsAheadNow("orders.t")).isEqualTo(3.0);

        // 그 뒤로 DB 가 내려가 점검이 계속 실패한다.
        clock.set("2026-12-01T00:00:00Z");
        m.runOnce();
        assertThat(m.monthsAheadNow("orders.t")).as("두 달이 지났다 — RunwayShort(<2)").isEqualTo(1.0);
        clock.set("2027-01-15T00:00:00Z");
        m.runOnce();
        assertThat(m.monthsAheadNow("orders.t")).as("다음 달 파티션이 없다 — MissingNextMonth(<1)").isEqualTo(0.0);
        clock.set("2027-02-01T00:00:00Z");
        assertThat(m.monthsAheadNow("orders.t")).as("이번 달 파티션도 없다").isEqualTo(-1.0);
    }

    @Test
    @DisplayName("🔴 마지막 성공 시각의 처음 값은 기동 시각 — 한 번도 못 돌아도 NaN 이 아니라서 경보가 잡는다. 실패는 그 값을 갱신하지 않는다")
    void lastSuccessStartsAtStartupAndOnlySuccessMovesIt() throws Exception {
        MutableClock clock = new MutableClock("2026-10-12T00:00:00Z");
        OutboxPartitionMaintainer m = new OutboxPartitionMaintainer(downDatabase(), List.of("orders.t"), 3, clock);
        long start = Instant.parse("2026-10-12T00:00:00Z").getEpochSecond();
        assertThat(m.lastSuccessEpochSeconds("orders.t")).isEqualTo((double) start);

        clock.set("2026-10-14T00:00:00Z");
        m.runOnce();
        assertThat(m.lastSuccessEpochSeconds("orders.t")).as("실패한 점검이 성공 시각을 옮겼다").isEqualTo((double) start);
        assertThat(m.defaultRows("orders.t")).isNaN();

        m.record("orders.t", new OutboxPartitionMaintainer.Status(3, YearMonth.of(2027, 1), 2, List.of()));
        assertThat(m.lastSuccessEpochSeconds("orders.t"))
                .isEqualTo((double) Instant.parse("2026-10-14T00:00:00Z").getEpochSecond());
        assertThat(m.defaultRows("orders.t")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("🔴 Error 가 던져져도 다음 주기가 돈다 — scheduleWithFixedDelay 는 한 번 새어 나간 예외로 이후 실행을 조용히 취소한다")
    void jobSurvivesErrors() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Runnable task = () -> {
            if (runs.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("simulated");
            }
        };
        OutboxPartitionAutoConfiguration.OutboxPartitionJob job = new OutboxPartitionAutoConfiguration.OutboxPartitionJob(
                task, "[orders.t]", Duration.ZERO, Duration.ofMillis(10));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (runs.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(runs.get()).as("첫 실행의 Error 뒤로 잡이 멈췄다").isGreaterThanOrEqualTo(3);
        } finally {
            job.destroy();
        }
    }
}
