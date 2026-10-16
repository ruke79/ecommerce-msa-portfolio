package com.project.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 멱등 원장 정리 — 예전에는 coupon · catalog · cart 어디에도 지우는 곳이 없어 처리한 이벤트마다 한 줄이 영구히 쌓였다.
 *
 * <p>[JA] 冪等台帳の掃除。以前は coupon・catalog・cart のどこにも削除する所がなく、処理したイベントごとに一行が永久に溜まっていた。
 */
@DisplayName("멱등 원장 정리 (ProcessedEventPurger)")
class ProcessedEventPurgerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T19:40:00Z"), ZoneId.of("Asia/Seoul"));

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    private ProcessedEventPurger purger(Duration retention) {
        return new ProcessedEventPurger(dataSource, "coupon.processed_event", retention, 100, 5, CLOCK);
    }

    @Test
    @DisplayName("보존 기간(30일) 이전만, 한 묶음씩 — 기준 시각은 원장과 같은 지역 시간")
    void deletesBeforeTheCutoffInBatches() throws Exception {
        when(statement.executeUpdate()).thenReturn(3);

        assertThat(purger(Duration.ofDays(30)).purge()).isEqualTo(3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("DELETE FROM coupon.processed_event WHERE event_id IN "
                + "(SELECT event_id FROM coupon.processed_event WHERE processed_at < ? LIMIT ?)");
        verify(statement).setTimestamp(1, Timestamp.valueOf(LocalDateTime.of(2026, 9, 16, 4, 40)));
        verify(statement).setInt(2, 100);
    }

    @Test
    @DisplayName("한 묶음이 꽉 차면 다시 지우고, 덜 차면 멈춘다")
    void loopsUntilAPartialBatch() throws Exception {
        when(statement.executeUpdate()).thenReturn(100, 100, 7);

        assertThat(purger(Duration.ofDays(30)).purge()).isEqualTo(207);
        verify(statement, times(3)).executeUpdate();
    }

    @Test
    @DisplayName("한 회차는 maxBatches 번에서 멈춘다 — 남은 행은 다음 회차")
    void stopsAtTheCap() throws Exception {
        when(statement.executeUpdate()).thenReturn(100);

        assertThat(purger(Duration.ofDays(30)).purge()).isEqualTo(500);
        verify(statement, times(5)).executeUpdate();
    }

    @Test
    @DisplayName("🔴 보존을 짧게 적어도 22일(DLT 14일 + 토픽 7일 + 하루)은 남긴다")
    void retentionNeverDropsBelowTheRedeliveryWindow() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);

        ProcessedEventPurger p = purger(Duration.ofDays(1));
        p.purge();

        assertThat(p.retention()).isEqualTo(Duration.ofDays(22));
        verify(statement).setTimestamp(1, Timestamp.valueOf(LocalDateTime.of(2026, 9, 24, 4, 40)));
        assertThat(purger(null).retention()).isEqualTo(Duration.ofDays(22));
        assertThat(purger(Duration.ofDays(90)).retention()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    @DisplayName("자동 커밋이 꺼진 연결이면 묶음마다 커밋한다 — 켜져 있으면 커밋하지 않는다")
    void commitsEachBatchOnManualConnections() throws Exception {
        when(statement.executeUpdate()).thenReturn(100, 4);

        purger(Duration.ofDays(30)).purge();
        verify(connection, never()).commit();

        when(connection.getAutoCommit()).thenReturn(false);
        when(statement.executeUpdate()).thenReturn(100, 4);
        purger(Duration.ofDays(30)).purge();
        verify(connection, times(2)).commit();
    }

    @Test
    @DisplayName("🔴 표 이름은 <스키마>.processed_event 모양만 — SQL 에 그대로 들어간다")
    void rejectsOtherTableNames() {
        for (String bad : new String[] {"coupon.coupon", "processed_event", "x.processed_event; drop table y", null}) {
            assertThatThrownBy(() -> new ProcessedEventPurger(dataSource, bad, Duration.ofDays(30), 100, 5, CLOCK))
                    .as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ProcessedEventPurger(dataSource, "cart.processed_event", Duration.ofDays(30), 0, 5, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
