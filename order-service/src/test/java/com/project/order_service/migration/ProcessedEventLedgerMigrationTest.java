package com.project.order_service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 처리한 이벤트 원장(V30) — V29 까지 올린 실제 PostgreSQL 에 주문을 심고 V30 을 적용한다. 원장의 INSERT … ON CONFLICT 가 같은 키를 한 번만
 * 받는지(ProcessedEventLedger.markFirst 의 문장 그대로), 정리가 쓰는 processed_at 인덱스가 있는지 본다.
 *
 * <p>[JA] 処理済みイベントの台帳(V30)— V29 まで上げた本物の PostgreSQL に V30 を適用し、同じキーを一度だけ受けるかを見る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("주문 처리 원장 (V30, PostgreSQL)")
class ProcessedEventLedgerMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("orderdb")
                    .withUsername("product")
                    .withPassword("testpw");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load();
    }

    private static final String MARK = "INSERT INTO orders.processed_event (event_id, processed_at) VALUES ('delivery-status:3:CANCELLED', now())"
            + " ON CONFLICT (event_id) DO NOTHING";

    @Test
    @DisplayName("🔴 V30 — 같은 키는 한 번만 들어가고(두 번째는 0줄), processed_at 인덱스가 있다")
    void ledgerTakesEachKeyOnce() throws SQLException {
        flyway("29").migrate();
        flyway("latest").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = db.createStatement()) {
            assertThat(st.executeUpdate(MARK)).as("처음").isEqualTo(1);
            assertThat(st.executeUpdate(MARK)).as("다시 — 건너뜀").isZero();
            try (ResultSet rs = st.executeQuery("select count(*) from pg_indexes where schemaname = 'orders'"
                    + " and tablename = 'processed_event' and indexdef like '%(processed_at)%'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("정리(ProcessedEventPurger)가 쓰는 인덱스").isEqualTo(1);
            }
        }
    }
}
