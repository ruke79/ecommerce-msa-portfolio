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
 * contract(V24, 실제 PostgreSQL) — 옛 이름 검색 인덱스(V21 의 idx_product_name_trgm, lower(name))를 지운다. 이름 검색은 V23 의
 * search_key 식 인덱스를 그대로 쓴다.
 *
 * <p>{@code DROP INDEX CONCURRENTLY} 는 트랜잭션 안에서 돌 수 없다 — Flyway(11.7.2)가 그 문장을 알아보고 이 파일을 트랜잭션 밖에서
 * 돌리는지를 실제 Flyway 로 본다({@code .sql.conf} 없이 — 2026-10-31 확인). 보통의 DROP INDEX 는 상품 표에 ACCESS EXCLUSIVE 를 잡아, 필터 메뉴 계산(최대 600초)
 * 뒤에 줄을 서면 그동안 상품 읽기가 모두 멈춘다(docs/85 §8-1). DB 소유자(슈퍼유저 아님) 역할로 돌린다 — 운영과 같은 권한.
 *
 * <p>[JA] contract(V24)— 旧名前検索インデックスを CONCURRENTLY で削除する。トランザクション外で流れるかを実際の Flyway で見る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("contract — 옛 이름 검색 인덱스 삭제 (V24, PostgreSQL — CONCURRENTLY)")
class ProductNameTrgmDropMigrationTest {

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
    @DisplayName("🔴 V23: 이름 인덱스가 둘(lower · search_key) → V24: 옛 것만 사라지고 이름 검색은 search_key 인덱스를 쓴다")
    void dropsTheOldNameIndexConcurrently() throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            exec(admin, "create role " + OWNER + " login nosuperuser password '" + OWNER_PW + "'");
            exec(admin, "create database productdb owner " + OWNER);
        }
        flyway("23").migrate();
        try (Connection db = DriverManager.getConnection(ownerUrl(), OWNER, OWNER_PW)) {
            exec(db, "insert into product.category (category_id, category_name, slug) values (1, 'c1', 'c-1')");
            exec(db, "insert into product.product (product_id, category_id, name, slug, brand, description, status, rating, version) "
                    + "select g, 1, 'Classic ' || case when g % 97 = 0 then 'Hoodie' else 'Shirt' end || ' ' || g, 'p-' || g, 'b', "
                    + "repeat('d', 1000), 'ACTIVE', 0, 0 from generate_series(1, 30000) g");
            assertThat(scalar(db, "select count(*) from pg_indexes where schemaname = 'product' "
                    + "and indexname in ('idx_product_name_trgm', 'idx_product_name_search_key_trgm')")).as("V23 — 이름 인덱스 둘").isEqualTo(2);

            flyway("latest").migrate();
            exec(db, "vacuum analyze product.product");

            assertThat(scalar(db, "select count(*) from pg_indexes where schemaname = 'product' and indexname = 'idx_product_name_trgm'"))
                    .as("V24 — 옛 lower(name) 인덱스가 없다").isZero();
            assertThat(scalar(db, "select count(*) from pg_index x join pg_class i on i.oid = x.indexrelid "
                    + "where i.relname = 'idx_product_name_search_key_trgm' and x.indisvalid")).as("search_key 인덱스는 그대로(유효)").isEqualTo(1);
            assertThat(scalar(db, "select count(*) from product.flyway_schema_history where version = '24' and success"))
                    .as("트랜잭션 밖에서 성공(CONCURRENTLY)").isEqualTo(1);
            String name = "select count(*) from product.product where status <> 'DELETED' "
                    + "and product.search_key(name) like '%' || product.search_key('hoodie') || '%' escape '!'";
            assertThat(plan(db, name)).as("이름 검색 — search_key 식 인덱스").contains("idx_product_name_search_key_trgm");
            assertThat(scalar(db, name)).isEqualTo(30000 / 97);
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
