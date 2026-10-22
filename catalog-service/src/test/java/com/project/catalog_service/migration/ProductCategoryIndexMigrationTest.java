package com.project.catalog_service.migration;

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
 * 카테고리 질의가 카테고리 인덱스를 쓴다(V20, 실제 PostgreSQL).
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5): 카테고리 검색의 개수 세기 · 브랜드 DISTINCT 가 category_id 를 맨 뒤 열로 가진
 * idx_product 를 통째로 훑었다(books 13만 개에 720MB). V19 까지 올린 DB 에 상품을 넣고 V20 전후의 실행 계획을 본다.
 *
 * <p>[JA] カテゴリ照会がカテゴリのインデックスを使う(V20)。V19 まで上げた DB に商品を入れ、V20 の前後の実行計画を比べる。
 */
@Tag("containers")
@Testcontainers
@DisplayName("상품 카테고리 인덱스 (V20, PostgreSQL)")
class ProductCategoryIndexMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
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
    @DisplayName("🔴 V19: 개수 세기가 idx_product 를 통째로 → V20: idx_product_category 로 그 카테고리만 — 개수 · 목록 · 브랜드 질의 모두")
    void categoryQueriesUseTheCategoryIndex() throws SQLException {
        flyway("19").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 큰 카테고리 하나(5만)와 작은 카테고리 스물(각 100) — 측정 데이터처럼 카테고리마다 크기가 다르다
            exec(db, "insert into product.category (category_id, category_name, slug) "
                    + "select g, 'c' || g, 'c-' || g from generate_series(1, 21) g");
            exec(db, "insert into product.product (product_id, category_id, name, slug, brand, status, rating, version) "
                    + "select g, case when g <= 50000 then 1 else 2 + (g % 20) end, 'p ' || g, 'p-' || g, 'b' || (g % 400), 'ACTIVE', 0, 0 "
                    + "from generate_series(1, 52000) g");
            exec(db, "vacuum analyze product.product");

            String count = "select count(*) from product.product where category_id = 7";
            String list = "select product_id from product.product where category_id = 7 order by product_id desc offset 10 limit 10";
            String brands = "select distinct brand from product.product where category_id = 7";
            assertThat(plan(db, count)).as("V19 — 카테고리 인덱스가 없다").doesNotContain("idx_product_category");

            flyway("latest").migrate();
            exec(db, "analyze product.product");

            assertThat(plan(db, count)).as("개수 세기").contains("idx_product_category");
            assertThat(plan(db, list)).as("카테고리 목록(최신 순)").contains("idx_product_category");
            assertThat(plan(db, brands)).as("브랜드 메뉴").contains("idx_product_category");
            assertThat(scalar(db, count)).isEqualTo(100);
        }
    }

    private static String plan(Connection db, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("explain " + sql)) {
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

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
