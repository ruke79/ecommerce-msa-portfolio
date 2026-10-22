package com.project.catalog_service.migration;

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
 * 외래 키의 인덱스(V22, 실제 PostgreSQL) — 부모 행을 지울 때 Postgres 는 지운 행마다 자식 표에 "아직 가리키는 행이 있나" 를 묻는다.
 * 자식의 외래 키 열이 인덱스의 앞 열이 아니면 그 질의는 지운 행마다 자식 표 전체를 훑는다.
 *
 * <p>🐛 2026-10-19 데이터 규모 정리({@code ops/44 purge}, 상품 100만): 상품 20만 개 묶음의 {@code DELETE FROM product.product} 가
 * 끝나지 않았다 — {@code product_subcategory} 의 인덱스는 {@code (subcategory_id, product_id)} 뿐이라 상품 한 행마다 분류 연결 100만 줄을
 * 훑었다. 관리자의 상품 삭제도 같은 검사를 한다({@code product_details} · {@code product_qa} 도 인덱스가 없었다).
 *
 * <p>[JA] 外部キーのインデックス — 親の行を消すと Postgres は消した行ごとに子のテーブルを照会する。外部キー列がインデックスの先頭でなければ、
 *      その照会は行ごとに子のテーブル全体を走査する。ops/44 purge の商品削除が終わらなかった。
 */
@Tag("containers")
@Testcontainers
@DisplayName("외래 키의 인덱스 (V22, PostgreSQL)")
class ForeignKeyIndexMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load();
    }

    /** 부모를 지울 때 Postgres 가 자식 표에 묻는 것과 같은 모양(RI 트리거 — 값을 모르는 일반 계획). */
    private static final String RI_CHECK = "prepare ri_check(bigint) as select 1 from only product.product_subcategory x "
            + "where product_id = $1 for key share of x";

    /** 인덱스의 앞 열이 그 외래 키의 열이 아닌 외래 키(부분 인덱스는 치지 않는다). Spring Batch 의 meta 스키마는 그 쪽 DDL 이라 뺀다. */
    private static final String UNINDEXED_FKS = """
            select n.nspname || '.' || r.relname || '(' || (
                     select string_agg(a.attname, ', ' order by k.ord)
                       from unnest(c.conkey) with ordinality k(attnum, ord)
                       join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum) || ')'
              from pg_constraint c
              join pg_class r on r.oid = c.conrelid
              join pg_namespace n on n.oid = r.relnamespace
             where c.contype = 'f' and n.nspname in ('product', 'content')
               and not exists (
                     select 1 from pg_index i
                      where i.indrelid = c.conrelid and i.indpred is null
                        and array_to_string((i.indkey::int2[])[0:cardinality(c.conkey) - 1], ',')
                            = array_to_string(c.conkey, ','))
             order by 1""";

    @Test
    @DisplayName("🔴 V21: 상품을 지울 때의 분류 연결 검사가 표 전체를 훑는다 → V22: 인덱스 · 인덱스 없는 외래 키가 남지 않는다")
    void everyForeignKeyHasAnIndex() throws SQLException {
        flyway("21").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 데이터 규모 정리의 모양 — 상품마다 분류 연결 하나(10만)
            exec(db, "insert into product.category (category_id, category_name, slug) values (1, 'c', 'c')");
            exec(db, "insert into product.subcategory (subcategory_id, category_id, slug, subcategory_name) "
                    + "select g, 1, 's-' || g, 's' || g from generate_series(1, 40) g");
            exec(db, "insert into product.product (product_id, category_id, name, slug, brand, status, rating, version) "
                    + "select g, 1, 'p' || g, 'p-' || g, 'b', 'ACTIVE', 0, 0 from generate_series(1, 100000) g");
            exec(db, "insert into product.product_subcategory (product_id, subcategory_id, version) "
                    + "select g, 1 + g % 40, 0 from generate_series(1, 100000) g");
            exec(db, "vacuum analyze product.product_subcategory");
            exec(db, "set plan_cache_mode = force_generic_plan");

            exec(db, RI_CHECK);
            assertThat(plan(db, "execute ri_check(500)")).as("V21 — 지운 상품 한 행마다 표 전체").contains("Seq Scan");
            assertThat(rows(db, UNINDEXED_FKS)).as("V21 의 인덱스 없는 외래 키")
                    .containsExactly("product.product_details(product_id)", "product.product_qa(product_id)",
                            "product.product_subcategory(product_id)", "product.subcategory(category_id)");
            exec(db, "deallocate ri_check");

            flyway("latest").migrate();
            exec(db, "analyze product.product_subcategory");

            exec(db, RI_CHECK);
            assertThat(plan(db, "execute ri_check(500)")).contains("idx_product_subcategory_product_id");
            assertThat(rows(db, UNINDEXED_FKS)).as("인덱스 없는 외래 키 — 새 외래 키를 더하면 같은 마이그레이션에 인덱스도").isEmpty();
        }
    }

    private static String plan(Connection db, String sql) throws SQLException {
        return String.join("\n", rows(db, "explain " + sql));
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
