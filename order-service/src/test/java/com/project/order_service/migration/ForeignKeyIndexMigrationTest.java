package com.project.order_service.migration;

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
 * 외래 키의 인덱스(V28, 실제 PostgreSQL) — 주문 줄을 주문으로 읽을 때와 주문을 지울 때(지운 주문마다 Postgres 가 줄 표에 묻는다)
 * 줄 표의 order_id 인덱스가 없으면 표 전체를 훑는다.
 *
 * <p>🐛 2026-10-20 주문 이력 생성기 시험(docs/83 §2.4): 줄 40만에서 주문 상세의 줄 조회가 Parallel Seq Scan 27ms, 주문 2만 개 삭제가
 * 9분 20초였다(catalog V22 와 같은 결함 — catalog-service 의 같은 이름 시험).
 *
 * <p>[JA] 外部キーのインデックス(V28、実際の PostgreSQL)— 注文明細を注文で読むときと注文を消すとき(消した注文ごとに Postgres が
 * 明細表に問い合わせる)、明細表の order_id インデックスがないと表全体を走査する。
 *
 * <p>[JA] 🐛 2026-10-20 注文履歴生成器の試験(docs/83 §2.4): 明細 40 万で注文詳細の明細照会が Parallel Seq Scan 27ms、注文 2 万個の削除が
 * 9 分 20 秒だった(catalog V22 と同じ欠陥 — catalog-service の同名の試験)。
 */
@Tag("containers")
@Testcontainers
@DisplayName("외래 키의 인덱스 (V28, PostgreSQL)")
class ForeignKeyIndexMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("orderdb")
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

    /** 주문을 지울 때 Postgres 가 줄 표에 묻는 것과 같은 모양(RI 트리거 — 값을 모르는 일반 계획). 주문 상세의 줄 조회도 같은 조건이다. */
    // [JA] 注文を削除するとき Postgres が行テーブルに問い合わせるのと同じ形(RI トリガー — 値を知らない汎用プラン)。
    //      注文詳細の行照会も同じ条件である。
    private static final String RI_CHECK = "prepare ri_check(bigint) as select 1 from only orders.order_product x "
            + "where order_id = $1 for key share of x";

    /** 인덱스의 앞 열이 그 외래 키의 열이 아닌 외래 키(부분 인덱스는 치지 않는다). */
    // [JA] インデックスの先頭列がその外部キーの列ではない外部キー(部分インデックスは数えない)。
    private static final String UNINDEXED_FKS = """
            select n.nspname || '.' || r.relname || '(' || (
                     select string_agg(a.attname, ', ' order by k.ord)
                       from unnest(c.conkey) with ordinality k(attnum, ord)
                       join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum) || ')'
              from pg_constraint c
              join pg_class r on r.oid = c.conrelid
              join pg_namespace n on n.oid = r.relnamespace
             where c.contype = 'f' and n.nspname = 'orders'
               and not exists (
                     select 1 from pg_index i
                      where i.indrelid = c.conrelid and i.indpred is null
                        and array_to_string((i.indkey::int2[])[0:cardinality(c.conkey) - 1], ',')
                            = array_to_string(c.conkey, ','))
             order by 1""";

    @Test
    @DisplayName("🔴 V27: 주문 줄을 주문으로 찾으면 표 전체를 훑는다 → V28: 인덱스 · 인덱스 없는 외래 키가 남지 않는다")
    void everyForeignKeyHasAnIndex() throws SQLException {
        flyway("27").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 주문 5만 · 줄 10만(주문마다 둘)
            // [JA] 注文 5 万・行 10 万(注文ごとに二つ)
            exec(db, "insert into orders.orders (order_id, customer_id, tracking_id, order_status, payment_method, shipping_price, "
                    + "total_before_discount, total, created_at) select g, 1 + g % 500, 't-' || g, 'COMPLETED', 'cash', 0, 10, 10, now() "
                    + "from generate_series(1, 50000) g");
            exec(db, "insert into orders.order_product (order_product_id, order_id, product_id, name, size, qty, price) "
                    + "select g, 1 + g % 50000, 1000001 + g % 900, 'p', '', 1, 10 from generate_series(1, 100000) g");
            exec(db, "vacuum analyze orders.order_product");
            exec(db, "set plan_cache_mode = force_generic_plan");

            exec(db, RI_CHECK);
            assertThat(plan(db, "execute ri_check(500)")).as("V27 — 주문 하나의 줄을 찾는 데 표 전체").contains("Seq Scan");
            assertThat(rows(db, UNINDEXED_FKS)).as("V27 의 인덱스 없는 외래 키").containsExactly("orders.order_product(order_id)");
            exec(db, "deallocate ri_check");

            flyway("latest").migrate();
            exec(db, "analyze orders.order_product");
            exec(db, RI_CHECK);
            assertThat(plan(db, "execute ri_check(500)")).contains("idx_order_product_order_id");
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
