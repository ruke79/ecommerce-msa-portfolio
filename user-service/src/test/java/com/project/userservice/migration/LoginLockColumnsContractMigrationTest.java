package com.project.userservice.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * contract(V19, 실제 PostgreSQL) — 로그인 실패 잠금의 옛 열(V4) 둘을 지운다. 잠금은 Redis(LoginAttemptService)이고 엔티티에는
 * 이미 필드가 없다(UserLockFieldsTest). 사용자 행은 그대로이고, 열 없이 가입(INSERT)이 된다.
 *
 * <p>[JA] contract(V19)— ログイン失敗ロックの旧列二つを削除する。ユーザー行はそのまま、列なしで INSERT できる。
 */
@Tag("containers")
@Testcontainers
@DisplayName("contract — 로그인 실패 잠금의 옛 열 삭제 (V19, PostgreSQL)")
class LoginLockColumnsContractMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("userdb").withUsername("product").withPassword("testpw");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load();
    }

    private static final String LOCK_COLUMNS = "select column_name from information_schema.columns where table_schema = 'users' "
            + "and table_name = 'users' and column_name in ('lock_expires_at', 'failed_login_attempts') order by 1";

    @Test
    @DisplayName("🔴 V18: 옛 잠금 열 둘이 남아 있다 → V19: 열이 사라지고 사용자 행은 그대로 · 열 없이 가입된다")
    void dropsTheOldLockColumns() throws SQLException {
        flyway("18").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 옛 모양: Redis 로 옮기기 전 코드가 남긴 값(실패 3회 · 잠금 시각)
            exec(db, "INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked, "
                    + "credentials_non_expired, email_verified, enabled, is_two_factor_enabled, failed_login_attempts, lock_expires_at) "
                    + "VALUES (1, 'u1', 'u1@example.com', true, true, true, true, true, false, 3, now() + interval '10 minutes')");
            assertThat(rows(db, LOCK_COLUMNS)).as("V18 — 옛 열 둘").containsExactly("failed_login_attempts", "lock_expires_at");

            flyway("latest").migrate();

            assertThat(rows(db, LOCK_COLUMNS)).as("V19 — 두 열이 없다").isEmpty();
            assertThat(rows(db, "SELECT user_id || ':' || email FROM users.users")).as("사용자 행은 그대로").containsExactly("1:u1@example.com");
            // 지금 코드의 가입 모양(두 열 없이)
            exec(db, "INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked, "
                    + "credentials_non_expired, email_verified, enabled, is_two_factor_enabled) "
                    + "VALUES (2, 'u2', 'u2@example.com', true, true, true, true, true, false)");
            assertThat(rows(db, "SELECT count(*)::text FROM users.users")).containsExactly("2");
        }
    }

    private static List<String> rows(Connection db, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }
}
