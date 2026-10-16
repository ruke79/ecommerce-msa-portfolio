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
 * 쿠폰을 쓴 주문(V6) — 옛 행을 그대로 두고 널 허용 열만 더하는지, 실제 PostgreSQL 에서. V5 까지 올리고 옛 USED · NOT_USED 를 심은 뒤
 * V6 를 적용한다.
 *
 * <p>[JA] クーポンを使った注文(V6) — 既存行をそのままにして NULL 許容列だけを足すか、実際の PostgreSQL で。
 */
@Tag("containers")
@Testcontainers
@DisplayName("쿠폰을 쓴 주문 (V6, PostgreSQL)")
class CouponUsedOrderMigrationTest {

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
    @DisplayName("옛 USED · NOT_USED 행은 그대로, used_order_id 는 NULL — 다시 적용해도 무동작")
    void addsNullableColumnKeepingRows() throws SQLException {
        flyway("5").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version) values (1, 7, 'WELCOME', 10, 'USED', 'ALL', 0)");
            exec(db, "insert into coupon.coupon (coupon_id, user_id, name, discount, status, scope, version) values (2, 7, 'SUMMER', 20, 'NOT_USED', 'ALL', 0)");

            flyway("latest").migrate();

            assertThat(one(db, "select count(*) from coupon.coupon")).isEqualTo("2");
            assertThat(one(db, "select status from coupon.coupon where coupon_id = 1")).isEqualTo("USED");
            assertThat(one(db, "select coalesce(cast(used_order_id as text), 'null') from coupon.coupon where coupon_id = 1"))
                    .as("어느 주문이 썼는지 모른다 — 보상은 예전처럼 되돌린다").isEqualTo("null");
            exec(db, "update coupon.coupon set used_order_id = 9007199254740993 where coupon_id = 2");
            assertThat(one(db, "select used_order_id from coupon.coupon where coupon_id = 2")).as("TSID 범위(bigint)").isEqualTo("9007199254740993");
            exec(db, "ALTER TABLE coupon.coupon ADD COLUMN IF NOT EXISTS used_order_id bigint");
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
