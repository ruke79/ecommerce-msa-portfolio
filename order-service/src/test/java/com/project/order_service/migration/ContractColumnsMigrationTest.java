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
 * contract(V29, 실제 PostgreSQL) — V25(expand)에서 쓰지 않게 된 order_address.order_id · orders.tax_price 를 지운다.
 * 열에 걸린 외래 키 · 인덱스가 함께 사라지고, 주문 · 주소 데이터와 주문 쪽 한 방향 유니크(uq_orders_order_address)는 그대로다.
 *
 * <p>[JA] contract(V29)— V25 で使わなくなった二列を削除する。外部キー · インデックスも一緒に消え、注文 · 住所のデータと
 * 注文側のユニークは残る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("contract — 주소의 order_id · 세금 열 삭제 (V29, PostgreSQL)")
class ContractColumnsMigrationTest {

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

    private static final String COLUMNS = "select table_name || '.' || column_name from information_schema.columns "
            + "where table_schema = 'orders' and ((table_name = 'order_address' and column_name = 'order_id') "
            + "or (table_name = 'orders' and column_name = 'tax_price')) order by 1";

    /** order_address 에 걸린 제약 · 인덱스 중 order_id 를 쓰는 것. */
    private static final String ADDRESS_ORDER_ID_OBJECTS = """
            select c.conname from pg_constraint c
              join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
             where c.conrelid = 'orders.order_address'::regclass and a.attname = 'order_id'
            union
            select i.relname from pg_index x
              join pg_class i on i.oid = x.indexrelid
              join pg_attribute a on a.attrelid = x.indrelid and a.attnum = any(x.indkey)
             where x.indrelid = 'orders.order_address'::regclass and a.attname = 'order_id'
             order by 1""";

    @Test
    @DisplayName("🔴 V28: 옛 열 둘과 주소 → 주문 외래 키가 남아 있다 → V29: 열 · 외래 키 · 인덱스가 사라지고 주문 · 주소는 그대로")
    void dropsTheExpandedColumns() throws SQLException {
        flyway("28").migrate();
        try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // 옛 모양(4415e498565c 전 코드가 만든 주문): 주소가 주문을 가리키고 세금 열이 채워져 있다
            exec(db, "insert into orders.order_address (order_address_id, firstname, lastname, address1, city, state, zip_code, country) "
                    + "values (1, '길동', '홍', '세종대로 1', '서울', '서울', '04524', 'KR'), (2, 'Taro', 'Yamada', '1-1', 'Tokyo', 'Tokyo', '1000001', 'JP')");
            exec(db, "insert into orders.orders (order_id, customer_id, tracking_id, order_status, payment_method, shipping_price, "
                    + "total_before_discount, total, tax_price, order_address_id, created_at) values "
                    + "(10, 1, 't-10', 'COMPLETED', 'cash', 0, 10, 10, 0.50, 1, now()), "
                    + "(11, 2, 't-11', 'NOT_PROCESSED', 'cash', 0, 20, 20, 0, 2, now())");
            exec(db, "update orders.order_address set order_id = 10 where order_address_id = 1");

            assertThat(rows(db, COLUMNS)).as("V28 — 옛 열 둘").containsExactly("order_address.order_id", "orders.tax_price");
            assertThat(rows(db, ADDRESS_ORDER_ID_OBJECTS)).as("V28 — 주소 → 주문 외래 키 · 인덱스").isNotEmpty();

            flyway("latest").migrate();

            assertThat(rows(db, COLUMNS)).as("V29 — 두 열이 없다").isEmpty();
            assertThat(rows(db, "select conname from pg_constraint where conrelid = 'orders.order_address'::regclass and contype = 'f'"))
                    .as("주소 → 주문 외래 키가 열과 함께 사라졌다 — 이제 주문 → 주소 한 방향").isEmpty();
            assertThat(rows(db, "select to_regclass('orders.idx_order_address')::text")).containsExactly((String) null);
            assertThat(rows(db, "select order_id || ':' || order_address_id || ':' || total from orders.orders order by order_id"))
                    .as("주문 데이터는 그대로").containsExactly("10:1:10.00", "11:2:20.00");
            assertThat(rows(db, "select count(*)::text from orders.order_address")).containsExactly("2");
            assertThat(rows(db, "select to_regclass('orders.uq_orders_order_address')::text"))
                    .as("주문당 주소 하나는 주문 쪽 유니크가 지킨다(V25)").containsExactly("orders.uq_orders_order_address");
            // 새 코드가 쓰는 모양(열 없이)으로 주문이 써진다
            exec(db, "insert into orders.order_address (order_address_id, firstname, lastname, address1, city, state, zip_code, country) "
                    + "values (3, 'a', 'b', 'c', 'd', 'e', '1', 'KR')");
            exec(db, "insert into orders.orders (order_id, customer_id, tracking_id, order_status, payment_method, shipping_price, "
                    + "total_before_discount, total, order_address_id, created_at) values (12, 3, 't-12', 'NOT_PROCESSED', 'cash', 0, 5, 5, 3, now())");
        }
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
