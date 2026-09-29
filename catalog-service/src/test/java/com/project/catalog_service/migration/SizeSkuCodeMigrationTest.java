package com.project.catalog_service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 사이즈 단위 SKU(V17) — 규칙이 DB 함수 {@code product.assign_size_sku_codes()} 한 곳에 있으므로 <b>실제 PostgreSQL</b>
 * 에서 고정한다. 정규식 · 창 함수 · 부분 UNIQUE 인덱스는 H2 나 목(mock)으로는 확인할 수 없다.
 *
 * <p>Spring 컨텍스트 없이 Flyway 만 돌린다 — 확인할 것은 SQL 이다.
 */
@Tag("containers")
@Testcontainers
@DisplayName("사이즈 단위 SKU 코드 (V17, PostgreSQL)")
class SizeSkuCodeMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private Connection db;
    private long nextId = 1;

    @BeforeEach
    void clean() throws SQLException {
        db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        exec("delete from product.product_size");
        exec("delete from product.product_sku");
        exec("delete from product.product");
        exec("delete from product.category where category_id = 1");
        exec("insert into product.category (category_id, category_name, slug) values (1, 'c', 'c')");
        exec("insert into product.product (product_id, category_id, name, slug, status, rating) values (1, 1, 'p', 'p-1', 'ACTIVE', 0)");
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    /** 스타일 하나를 만들고 그 id. */
    private long style(String code) throws SQLException {
        long id = nextId++;
        exec("insert into product.product_sku (skuproduct_id, product_id, sku, discount, sold) values ("
                + id + ", 1, " + (code == null ? "null" : "'" + code + "'") + ", 0, 0)");
        return id;
    }

    /** 사이즈 한 줄을 만들고 그 id. */
    private long size(long styleId, String name) throws SQLException {
        long id = 1000 + nextId++;
        exec("insert into product.product_size (size_id, skuproduct_id, size, quantity, price) values ("
                + id + ", " + styleId + ", " + (name == null ? "null" : "'" + name + "'") + ", 1, 10)");
        return id;
    }

    private int assign() throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("select product.assign_size_sku_codes()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Map<Long, String> codes() throws SQLException {
        Map<Long, String> out = new LinkedHashMap<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("select size_id, sku_code from product.product_size order by size_id")) {
            while (rs.next()) {
                out.put(rs.getLong(1), rs.getString(2));
            }
        }
        return out;
    }

    @Test
    @DisplayName("사이즈마다 '<스타일 코드>-<사이즈>' — 검정 270 과 280 은 코드가 다르다")
    void eachSizeGetsItsOwnCode() throws SQLException {
        long black = style("FAS-100042");
        long s270 = size(black, "270");
        long s280 = size(black, "280");
        long xl = size(black, "xl");

        assertThat(assign()).isEqualTo(3);

        assertThat(codes()).containsEntry(s270, "FAS-100042-270")
                .containsEntry(s280, "FAS-100042-280")
                .containsEntry(xl, "FAS-100042-XL");   // 대문자로
    }

    @Test
    @DisplayName("사이즈 없는 상품(빈 이름)은 스타일 코드 그대로 — 스타일 하나가 곧 재고 단위다")
    void sizelessProductUsesTheStyleCode() throws SQLException {
        long book = style("BOO-000003");
        long only = size(book, "");
        long nul = size(style("FOO-000001"), null);

        assign();

        assertThat(codes()).containsEntry(only, "BOO-000003").containsEntry(nul, "FOO-000001");
    }

    @Test
    @DisplayName("영숫자 아닌 글자는 '-' 하나로, 영숫자가 하나도 없으면 스타일 안 순번(-S<n>) — SKU 는 ASCII")
    void namesAreNormalizedToAscii() throws SQLException {
        long st = style("FAS-1");
        long mm = size(st, " 270 mm ");
        long korean = size(st, "프리");

        assign();

        assertThat(codes()).containsEntry(mm, "FAS-1-270-MM").containsEntry(korean, "FAS-1-S2");
    }

    /**
     * 시드 CSV 에 같은 스타일 · 같은 사이즈 이름이 두 줄인 경우가 실제로 있다(394쌍). 첫 줄만 원래 코드를 갖는다.
     * "-2" 가 아닌 이유 — 사이즈 "2" 의 코드와 겹친다.
     */
    @Test
    @DisplayName("같은 코드가 두 번 나오면 첫 줄만 그 코드, 나머지는 '-dup-<size_id>'")
    void duplicatesAreDisambiguated() throws SQLException {
        long st = style("FAS-7");
        long first = size(st, "M");
        long second = size(st, "M");
        long two = size(st, "2");

        assign();

        assertThat(codes()).containsEntry(first, "FAS-7-M")
                .containsEntry(second, "FAS-7-M-dup-" + second)
                .containsEntry(two, "FAS-7-2");
    }

    @Test
    @DisplayName("한 번 정한 코드는 바꾸지 않는다 — 다시 불러도 새 줄만 채운다")
    void assignedCodesAreStable() throws SQLException {
        long st = style("FAS-9");
        long m = size(st, "M");
        assign();
        exec("update product.product_size set size = 'L' where size_id = " + m);   // 이름이 바뀌어도
        long s = size(st, "S");

        assertThat(assign()).isEqualTo(1);
        assertThat(codes()).containsEntry(m, "FAS-9-M").containsEntry(s, "FAS-9-S");
    }

    @Test
    @DisplayName("다른 스타일의 기존 코드와 겹치면 새 줄이 '-dup-' 를 갖는다")
    void collisionWithAnExistingCodeIsAvoided() throws SQLException {
        long a = style("FAS-1");
        long am = size(a, "M");
        assign();
        long b = style("FAS-1-M");   // 사람이 이런 스타일 코드를 직접 넣었다
        long bOnly = size(b, "");

        assign();

        assertThat(codes()).containsEntry(am, "FAS-1-M").containsEntry(bOnly, "FAS-1-M-dup-" + bOnly);
    }

    @Test
    @DisplayName("스타일 코드가 없거나 부모가 없는 줄은 만들 수 없으므로 비워 둔다")
    void orphansStayEmpty() throws SQLException {
        long noCode = size(style(null), "M");
        long orphan = 1000 + nextId++;
        exec("insert into product.product_size (size_id, skuproduct_id, size, quantity, price) values ("
                + orphan + ", null, 'M', 1, 10)");

        assertThat(assign()).isZero();
        assertThat(codes()).containsEntry(noCode, null).containsEntry(orphan, null);
    }

    @Test
    @DisplayName("UNIQUE 인덱스가 최후 방어선 — 같은 코드를 손으로 넣어도 거절된다")
    void uniqueIndexGuards() throws SQLException {
        long st = style("FAS-5");
        size(st, "M");
        assign();
        long other = size(st, "L");

        assertThatThrownBy(() -> exec("update product.product_size set sku_code = 'FAS-5-M' where size_id = " + other))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_product_size_sku_code");
    }
}
