package com.project.coupon_service.migration;

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
 * 사용자의 쿠폰 조회 인덱스(V8) — (user_id, status) 가 생기고, 유니크와 겹치던 idx_coupon 이 지워지고, 행은 그대로인지. V7 까지 올리고
 * 행을 심은 뒤 V8 을 적용한다.
 */
@Tag("containers")
@Testcontainers
@DisplayName("쿠폰 사용자 인덱스 (V8, PostgreSQL)")
class CouponUserStatusIndexMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("coupondb")
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

    @Test
    @DisplayName("(user_id, status) 인덱스가 생기고 idx_coupon 은 사라진다 — 행 · 유니크는 그대로, 다시 적용해도 무동작")
    void replacesTheIndex() throws SQLException {
        flyway("7").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version) values (1, 7, 'WELCOME', 10, 'NOT_USED', 'ALL', 0)");

            flyway("latest").migrate();

            assertThat(one(db, "select count(*) from pg_indexes where schemaname = 'coupon' and indexname = 'idx_coupon_user_status'")).isEqualTo("1");
            assertThat(one(db, "select count(*) from pg_indexes where schemaname = 'coupon' and indexname = 'idx_coupon'")).isEqualTo("0");
            assertThat(one(db, "select count(*) from coupon.coupon")).isEqualTo("1");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exec(db,
                    "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version) values (2, 7, 'WELCOME', 10, 'NOT_USED', 'ALL', 0)"))
                    .as("같은 사용자 · 같은 이름은 유니크가 여전히 막는다").isInstanceOf(SQLException.class);
            exec(db, "CREATE INDEX IF NOT EXISTS idx_coupon_user_status ON coupon.coupon (user_id, status)");
            exec(db, "DROP INDEX IF EXISTS coupon.idx_coupon");
        }
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static String one(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
