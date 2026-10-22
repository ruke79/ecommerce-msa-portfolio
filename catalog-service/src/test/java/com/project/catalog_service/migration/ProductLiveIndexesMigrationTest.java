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
 * 살아 있는 상품만의 인덱스(V21, 실제 PostgreSQL) — 앱이 보내는 모양의 질의(`status <> 'DELETED'` 가 늘 붙는다)로 실행 계획을 본다.
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5): Product 의 @SQLRestriction 때문에 카테고리 개수 세기가 V20 인덱스로 줄을 찾고도
 * 표를 다시 읽었고(13만 개에 Bitmap Heap Scan 102ms), 이름 검색 {@code lower(name) LIKE '%…%'} 는 표 전체를 훑었다(178ms).
 *
 * <p>Flyway 는 슈퍼유저가 아닌 <b>DB 소유자</b> 역할로 돌린다 — 운영 · 데모의 서비스 계정(product)과 같은 권한에서 pg_trgm(신뢰된 확장)을
 * 만들 수 있는지가 이 마이그레이션의 전제다. V20 까지 올린 DB 에 상품을 넣고 V21 전후를 비교한다.
 *
 * <p>[JA] 生きている商品だけのインデックス(V21)— アプリと同じ形の照会(status <> 'DELETED' が常に付く)で実行計画を見る。Flyway は
 *      スーパーユーザーではない DB 所有者のロールで回す(pg_trgm を作れるかが前提)。
 */
@Tag("containers")
@Testcontainers
@DisplayName("살아 있는 상품만의 인덱스 (V21, PostgreSQL — DB 소유자 권한)")
class ProductLiveIndexesMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres")
                    .withUsername("admin")
                    .withPassword("testpw");

    private static final String OWNER = "product";
    private static final String OWNER_PW = "ownerpw";

    private static String ownerUrl() {
        return postgres.getJdbcUrl().replace("/postgres", "/productdb");
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(ownerUrl(), OWNER, OWNER_PW)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("🔴 V20: 개수 세기가 표를 다시 읽고 이름 검색은 전체 스캔 → V21: 개수 세기는 인덱스만 · 이름 검색은 트라이그램 GIN(값을 바인드해도)")
    void liveIndexesServeTheAppQueries() throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 운영 · 데모와 같은 모양 — 서비스 계정은 슈퍼유저가 아니고 DB 의 소유자다(k8s/90 · ops/04 · ops/25)
            exec(admin, "create role " + OWNER + " login nosuperuser password '" + OWNER_PW + "'");
            exec(admin, "create database productdb owner " + OWNER);
        }
        flyway("20").migrate();
        try (Connection db = DriverManager.getConnection(ownerUrl(), OWNER, OWNER_PW)) {
            assertThat(scalar(db, "select count(*) from pg_roles where rolname = current_user and rolsuper")).as("슈퍼유저가 아니다").isZero();
            // 측정 데이터의 모양 — 가장 큰 카테고리가 13%(100만 중 13만), 줄 폭 ~1.4KB(설명 1,000자). 이름에 'Hoodie' 는 97개마다 하나.
            // (좁은 줄 · 한 카테고리가 대부분이면 표를 통째로 읽는 편이 싸서 플래너가 어느 인덱스도 쓰지 않는다 — 이 시험이 보려는 것이 아니다)
            exec(db, "insert into product.category (category_id, category_name, slug) "
                    + "select g, 'c' || g, 'c-' || g from generate_series(1, 8) g");
            exec(db, "insert into product.product (product_id, category_id, name, slug, brand, description, status, rating, version) "
                    + "select g, case when g % 100 < 13 then 1 else 2 + (g % 7) end, "
                    + "'Classic ' || case when g % 97 = 0 then 'Hoodie' else 'Shirt' end || ' ' || g, 'p-' || g, 'b' || (g % 400), "
                    + "repeat('d', 1000), 'ACTIVE', 0, 0 from generate_series(1, 100000) g");
            exec(db, "vacuum analyze product.product");

            String count = "select count(*) from product.product where status <> 'DELETED' and category_id = 1";
            String name = "select count(*) from product.product where status <> 'DELETED' and lower(name) like lower('%hoodie%') escape '!'";
            assertThat(plan(db, count)).as("V20 — 줄을 찾고도 표를 다시 읽는다").doesNotContain("Index Only Scan");
            assertThat(plan(db, name)).as("V20 — 이름 검색은 인덱스가 없다").doesNotContain("trgm");

            flyway("latest").migrate();
            exec(db, "vacuum analyze product.product");

            assertThat(plan(db, count)).as("개수 세기 — 살아 있는 상품의 인덱스만").contains("Index Only Scan using idx_product_category_live");
            assertThat(plan(db, "select product_id from product.product where status <> 'DELETED' and category_id = 1 "
                    + "order by product_id desc offset 900 limit 10")).as("카테고리 목록(최신 순)").contains("idx_product_category");
            assertThat(plan(db, name)).as("이름 검색").contains("idx_product_name_trgm");
            // 앱은 값을 바인드한다(lower(?) — Querydsl likeIgnoreCase) — 5번째 실행부터 쓰일 수 있는 일반 계획에서도
            exec(db, "set plan_cache_mode = force_generic_plan");
            exec(db, "prepare by_name(text) as select count(*) from product.product where status <> 'DELETED' "
                    + "and lower(name) like lower($1) escape '!'");
            assertThat(plan(db, "execute by_name('%hoodie%')")).as("일반 계획(값을 모르는 채 세운 계획)").contains("idx_product_name_trgm");
            assertThat(scalar(db, name)).as("값은 그대로 — 100,000 / 97").isEqualTo(1030);
            assertThat(scalar(db, count)).isEqualTo(13000);
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
