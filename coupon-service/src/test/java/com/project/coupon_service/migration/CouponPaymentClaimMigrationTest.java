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
 * 결제 중인 주문(V7) — 옛 행을 그대로 두고 널 허용 열 둘만 더하는지, 실제 PostgreSQL 에서. V6 까지 올리고 옛 행을 심은 뒤 V7 을 적용한다.
 *
 * <p>[JA] 決済中の注文(V7) — 既存行をそのままにして NULL 許容列を二つ足すか、実際の PostgreSQL で。
 */
@Tag("containers")
@Testcontainers
@DisplayName("결제 중인 주문 (V7, PostgreSQL)")
class CouponPaymentClaimMigrationTest {

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
    @DisplayName("옛 행은 결제 중이 아니다(둘 다 NULL) — 주문 id(TSID) · 시각을 담고, 다시 적용해도 무동작")
    void addsNullableColumnsKeepingRows() throws SQLException {
        flyway("6").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version, used_order_id) values (1, 7, 'WELCOME', 10, 'USED', 'ALL', 0, 5)");
            exec(db, "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version) values (2, 7, 'SUMMER', 20, 'NOT_USED', 'ALL', 0)");

            flyway("latest").migrate();

            assertThat(one(db, "select count(*) from coupon.coupon")).isEqualTo("2");
            assertThat(one(db, "select count(*) from coupon.coupon where payment_order_id is null and payment_until is null"))
                    .as("옛 행은 결제 중이 아니다").isEqualTo("2");
            assertThat(one(db, "select used_order_id from coupon.coupon where coupon_id = 1")).isEqualTo("5");
            exec(db, "update coupon.coupon set payment_order_id = 9007199254740993, payment_until = '2026-10-15 12:30:00.123456' where coupon_id = 2");
            assertThat(one(db, "select payment_order_id from coupon.coupon where coupon_id = 2")).isEqualTo("9007199254740993");
            assertThat(one(db, "select payment_until from coupon.coupon where coupon_id = 2")).isEqualTo("2026-10-15 12:30:00.123456");
            exec(db, "ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS payment_order_id bigint");
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
