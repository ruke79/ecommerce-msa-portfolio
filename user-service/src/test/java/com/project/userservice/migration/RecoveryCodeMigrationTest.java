package com.project.userservice.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * 2단계 인증 복구 코드 표(V21, 실제 PostgreSQL) — 새 표 하나, 같은 사용자 · 같은 해시는 한 번, 리포지토리의 "쓰지 않은 행만" UPDATE 는
 * 두 번째에 0 행(docs/57 §22). [JA] リカバリーコード表(V21)— 同じ利用者 · 同じハッシュは一つ、二度目の消費は 0 行。
 */
@Tag("containers")
@Testcontainers
@DisplayName("2단계 인증 복구 코드 표 (V21, PostgreSQL)")
class RecoveryCodeMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    @Test
    @DisplayName("🔴 V20 → V21: 표가 생기고, 같은 코드는 한 번만 쓰이며, 중복 해시는 거절된다")
    void createsTableAndConsumesOnce() throws SQLException {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0").target("latest")
                .load().migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement st = db.createStatement()) {
            st.execute("INSERT INTO users.two_factor_recovery_code (user_id, code_hash, created_at) VALUES (42, 'h1', now())");
            String consume = "UPDATE users.two_factor_recovery_code SET used_at = now() "
                    + "WHERE user_id = 42 AND code_hash = 'h1' AND used_at IS NULL";
            assertThat(st.executeUpdate(consume)).isEqualTo(1);
            assertThat(st.executeUpdate(consume)).as("두 번째는 0 행 — 한 번만 쓴다").isZero();
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO users.two_factor_recovery_code (user_id, code_hash, created_at) VALUES (42, 'h1', now())"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
