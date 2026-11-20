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
 * SMS 2단계 인증의 열(V20, 실제 PostgreSQL) — 더하기만 한다. 옛 행은 꺼짐 · 결속값 없음이 되고, 열을 모르는 옛 이미지의 가입(INSERT)도
 * 그대로 된다(되돌리기 안전, docs/24 §10).
 *
 * <p>[JA] SMS 二段階認証の列(V20)— 追加のみ。旧行は無効 · 結束値なしになり、列を知らない旧イメージの INSERT もそのまま通る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("SMS 2단계 인증 열 추가 (V20, PostgreSQL)")
class SmsTwoFactorColumnsMigrationTest {

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

    private static final String OLD_INSERT = "INSERT INTO users.users (user_id, username, email, account_non_expired, "
            + "account_non_locked, credentials_non_expired, email_verified, enabled, is_two_factor_enabled) "
            + "VALUES (%d, 'u%d', 'u%d@example.com', true, true, true, true, true, %s)";

    @Test
    @DisplayName("🔴 V19 의 사용자(TOTP 켠 사람 포함) → V20: SMS 는 꺼짐 · 결속값 없음, TOTP 는 그대로, 옛 모양 가입도 된다")
    void addsColumnsWithSafeDefaults() throws SQLException {
        flyway("19").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, String.format(OLD_INSERT, 1, 1, 1, "true"));
            exec(db, String.format(OLD_INSERT, 2, 2, 2, "false"));

            flyway("latest").migrate();

            assertThat(rows(db, "SELECT user_id || ':' || is_two_factor_enabled || ':' || sms_two_factor_enabled || ':' "
                    + "|| coalesce(sms_two_factor_binding, '-') FROM users.users ORDER BY user_id"))
                    .containsExactly("1:true:false:-", "2:false:false:-");
            // 열을 모르는 옛 이미지의 가입 — 롤링 중 · 되돌린 뒤에도 된다
            // [JA] 列を知らない旧イメージの登録 — ローリング中 · 戻した後でもできる
            exec(db, String.format(OLD_INSERT, 3, 3, 3, "false"));
            assertThat(rows(db, "SELECT sms_two_factor_enabled::text FROM users.users WHERE user_id = 3")).containsExactly("false");
            assertThat(rows(db, "SELECT character_maximum_length::text FROM information_schema.columns WHERE table_schema = 'users' "
                    + "AND table_name = 'users' AND column_name = 'sms_two_factor_binding'")).containsExactly("64");
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
