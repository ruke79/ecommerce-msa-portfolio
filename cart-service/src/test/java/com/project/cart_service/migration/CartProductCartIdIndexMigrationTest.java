package com.project.cart_service.migration;

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
 * 장바구니 줄의 외래 키 인덱스(V8) — 장바구니 읽기가 줄 표를 훑지 않는다, 실제 PostgreSQL 에서.
 *
 * <p>🐛 예전: {@code cart_product.cart_id} 에 인덱스가 없었다. 장바구니 읽기(findByUserId 의 @EntityGraph — 화면 · 체크아웃 · 담기 ·
 * 리스너 모두)는 {@code cart LEFT JOIN cart_product ON cart_id} 라 요청마다 줄 표 전체를 훑었다(order V28 · catalog V22 와 같은 결함).
 * V7 까지 올리고 장바구니 2,000개 · 줄 2만 개를 심은 뒤 V8 을 적용해, 같은 모양의 질의 계획이 그 인덱스를 쓰는지 본다.
 *
 * <p>[JA] カート行の外部キーインデックス(V8)— カート読み込みが行テーブルを走査しない。V7 まで上げて行を入れ、V8 後の計画を見る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("장바구니 줄 외래 키 인덱스 (V8, PostgreSQL)")
class CartProductCartIdIndexMigrationTest {

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

    /** findByUserId 의 @EntityGraph(attributePaths = "cartProducts") 가 내는 모양. */
    private static final String LOAD_CART = "explain select c.cart_id, cp.cartproduct_id, cp.qty from cart.cart c"
            + " left join cart.cart_product cp on cp.cart_id = c.cart_id where c.user_id = 1234";

    @Test
    @DisplayName("🔴 V8 뒤 장바구니 읽기는 줄 표를 훑지 않고 idx_cart_product_cart 로 그 장바구니의 줄만 읽는다")
    void cartLoadUsesTheForeignKeyIndex() throws SQLException {
        flyway("7").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(db, "insert into cart.cart (cart_id, user_id, version) select g, g, 0 from generate_series(1, 2000) g");
            exec(db, "insert into cart.cart_product (cartproduct_id, cart_id, product_id, color_id, size, style, qty, price, shipping,"
                    + " selected, version) select g, 1 + g % 2000, g, 5, 'M', 0, 1, 10, 0, true, 0 from generate_series(1, 20000) g");
            exec(db, "analyze cart.cart");
            exec(db, "analyze cart.cart_product");
            assertThat(plan(db)).as("V7 — 인덱스가 없어 줄 표를 통째로 읽는다(고치기 전의 모양)").contains("Seq Scan on cart_product");

            flyway("latest").migrate();
            exec(db, "analyze cart.cart_product");

            assertThat(plan(db)).contains("idx_cart_product_cart").doesNotContain("Seq Scan on cart_product");
        }
    }

    private static String plan(Connection db) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(LOAD_CART)) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
            }
        }
        return out.toString();
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }
}
