package com.project.cart_service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
 * 장바구니 사용자당 하나(V5) — 이미 생긴 중복을 <b>합친 뒤</b> 유니크를 거는지, 실제 PostgreSQL 에서.
 *
 * <p>Spring 컨텍스트 없이 Flyway 만 돌린다 — V4 까지 올리고 중복을 심은 뒤 V5 를 적용한다.
 */
@Tag("containers")
@Testcontainers
@DisplayName("장바구니 사용자당 하나 (V5, PostgreSQL)")
class CartOnePerUserMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("cartdb")
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
    @DisplayName("중복 장바구니를 최근 것 하나로 합치고(없는 줄만 옮김) user_id 를 유니크로 만든다")
    void mergesDuplicatesThenAddsUniqueIndex() throws SQLException {
        flyway("4").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 사용자 7 — 장바구니 둘. 최근 것(2)에 상품 100/M, 옛 것(1)에 100/M(겹침) · 200/L(옮겨야 함)
            exec(db, "insert into cart.cart (cart_id, user_id, version, updated_at) values (1, 7, 0, '2026-10-01 10:00')");
            exec(db, "insert into cart.cart (cart_id, user_id, version, updated_at) values (2, 7, 0, '2026-10-01 11:00')");
            line(db, 11, 1, 100, "M", "10.00", 1);
            line(db, 12, 1, 200, "L", "20.00", 2);
            line(db, 21, 2, 100, "M", "10.00", 3);
            // 사용자 8 — 하나뿐(그대로여야 한다)
            exec(db, "insert into cart.cart (cart_id, user_id, version, cart_total, updated_at) values (3, 8, 0, 99, '2026-10-01 09:00')");
            line(db, 31, 3, 300, "S", "5.00", 1);

            flyway("latest").migrate();

            assertThat(longs(db, "select cart_id from cart.cart where user_id = 7")).containsExactly(2L);
            assertThat(longs(db, "select cartproduct_id from cart.cart_product where cart_id = 2 order by cartproduct_id"))
                    .as("겹친 줄(11)은 버리고 남길 장바구니에 없던 줄(12)만 옮긴다").containsExactly(12L, 21L);
            assertThat(longs(db, "select count(*) from cart.cart_product where cart_id = 1")).containsExactly(0L);
            // 합계는 고른 줄로 다시: 20×2 + 10×3 = 70
            assertThat(decimal(db, "select cart_total from cart.cart where cart_id = 2")).isEqualByComparingTo("70");
            // 중복이 없던 사용자는 손대지 않는다
            assertThat(decimal(db, "select cart_total from cart.cart where cart_id = 3")).isEqualByComparingTo("99");
            assertThat(longs(db, "select count(*) from cart.cart_product where cart_id = 3")).containsExactly(1L);

            assertThatThrownBy(() -> exec(db, "insert into cart.cart (cart_id, user_id, version) values (4, 8, 0)"))
                    .hasMessageContaining("uq_cart_user");
        }
    }

    private static void line(Connection db, long id, long cartId, long productId, String size, String price, int qty)
            throws SQLException {
        exec(db, "insert into cart.cart_product (cartproduct_id, cart_id, product_id, color_id, size, style, qty, price, shipping, selected, version) values ("
                + id + ", " + cartId + ", " + productId + ", 5, '" + size + "', 0, " + qty + ", " + price + ", 0, true, 0)");
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static java.util.List<Long> longs(Connection db, String sql) throws SQLException {
        java.util.List<Long> out = new java.util.ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static BigDecimal decimal(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }
}
