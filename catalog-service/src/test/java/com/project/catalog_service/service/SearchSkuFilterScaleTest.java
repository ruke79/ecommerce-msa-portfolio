package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 가격 · 색상 · 사이즈 필터 검색 — 실제 PostgreSQL 에서, 상품 7만 개 카테고리와 할인 · 기획전이 섞인 작은 카테고리.
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 필터 검색을 초당 3번 섞자 catalog 3개가 20초 만에
 * {@code OutOfMemoryError: Java heap space} 로 쓰러졌다(실패 93.76%). 후보 SKU 를 카테고리와 상관없이 전부 앱으로 읽고(가격대는 하한
 * 이상인 사이즈 줄 전부) 맞는 id 를 {@code IN (…)} 으로 다시 보냈다 — 7만 개면 바인드 변수 한도(65,535)에도 걸린다. 이제 EXISTS 로
 * DB 안에서 판정한다. 값의 규칙(화면의 할인가 · 기획전은 높은 쪽 · 앞부분 일치)은 예전과 같아야 한다.
 *
 * <p>[JA] 価格 · 色 · サイズのフィルター検索 — 本物の PostgreSQL で。以前は候補を全部アプリに読み IN で送り直し、100 万商品で
 *      ヒープ不足になった(7 万件ならバインド変数の上限にも掛かる)。今は EXISTS で DB の中で判定する。
 */
@Tag("containers")
@SpringBootTest
@org.testcontainers.junit.jupiter.Testcontainers
@DisplayName("필터 검색 — 상품 7만 개 카테고리 · 할인가 · 기획전 (EXISTS)")
class SearchSkuFilterScaleTest {

    private static final long BIG = 9300L;
    private static final long SMALL = 9301L;
    /** 카테고리 전체에 기획전 20% 가 걸린 카테고리. */
    private static final long SALE = 9302L;
    private static final int PRODUCTS = 70_000;
    private static final long FIRST_ID = 9_300_000L;
    /** 작은 카테고리(SMALL)의 상품 — 정가 · 상시 할인 · 색상 · 사이즈가 다르다. */
    private static final long P_SKU30 = 9_400_001L;     // 100 · 상시 30% → 70 · Navy Blue · M
    private static final long P_PLAIN = 9_400_002L;     // 85 · 0% → 85 · Red · XL
    private static final long P_EVENT50 = 9_400_003L;   // 100 · 상시 10% · 상품 기획전 50% → 50 · Red · M
    /** 기획전 카테고리(SALE, 20%)의 상품. */
    private static final long P_BOTH = 9_400_004L;      // 100 · 0% · 상품 기획전 40% + 카테고리 20% → 60 · Red · L
    private static final long P_CATEGORY = 9_400_005L;  // 100 · 0% · 카테고리 20% → 80 · Red · L

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("productdb")
                    .withUsername("product")
                    .withPassword("testpw");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource-data.url", postgres::getJdbcUrl);
        registry.add("spring.datasource-data.username", postgres::getUsername);
        registry.add("spring.datasource-data.password", postgres::getPassword);
        registry.add("spring.datasource-meta.url", postgres::getJdbcUrl);
        registry.add("spring.datasource-meta.username", postgres::getUsername);
        registry.add("spring.datasource-meta.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);

        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("spring.elasticsearch.uris", () -> "http://localhost:9200");
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_USERNAME", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_PASSWORD", () -> "");
        registry.add("AMAZON_ACCESS_KEY", () -> "dummy");
        registry.add("AMAZON_SECRET_KEY", () -> "dummy");
        registry.add("AMAZON_BUCKET_NAME", () -> "dummy");
        registry.add("AMAZON_REGION", () -> "ap-northeast-2");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SearchService searchService;

    @Autowired
    private EventPricing eventPricing;

    /** SearchParamsRequest 는 protected 기본 생성자만 있다. */
    static class Params extends SearchParamsRequest {
        Params() { super(); }
    }

    @BeforeEach
    void seed() {
        Integer seeded = jdbc.queryForObject("select count(*) from product.product where category_id = ?", Integer.class, BIG);
        if (seeded == null || seeded != PRODUCTS) {
            jdbc.update("insert into product.category (category_id, category_name, slug) values (?, 'big', 'big'), (?, 'small', 'small'), (?, 'sale', 'sale')",
                    BIG, SMALL, SALE);
            jdbc.update("insert into product.product_color (color_id, color, version) values (9301, 'Red', 0), (9302, 'Navy Blue', 0)");
            // 큰 카테고리: 상품마다 SKU 하나(Red) · 사이즈 하나(M, 10.00)
            jdbc.update("""
                    insert into product.product (product_id, category_id, name, slug, brand, status, rating, version)
                    select ? + g, ?, 'big ' || g, 'big-' || g, 'b', 'ACTIVE', 0, 0 from generate_series(1, ?) g
                    """, FIRST_ID, BIG, PRODUCTS);
            jdbc.update("""
                    insert into product.product_sku (skuproduct_id, product_id, color_id, discount, sold, version)
                    select ? + g, ? + g, 9301, 0, 0, 0 from generate_series(1, ?) g
                    """, FIRST_ID, FIRST_ID, PRODUCTS);
            jdbc.update("""
                    insert into product.product_size (skuproduct_id, size, price, quantity, version)
                    select ? + g, 'M', 10, 5, 0 from generate_series(1, ?) g
                    """, FIRST_ID, PRODUCTS);

            Object[][] small = { { P_SKU30, "100", 30, 9302, "M", SMALL }, { P_PLAIN, "85", 0, 9301, "XL", SMALL },
                    { P_EVENT50, "100", 10, 9301, "M", SMALL }, { P_BOTH, "100", 0, 9301, "L", SALE }, { P_CATEGORY, "100", 0, 9301, "L", SALE } };
            for (Object[] p : small) {
                long id = (Long) p[0];
                jdbc.update("insert into product.product (product_id, category_id, name, slug, brand, status, rating, version) "
                        + "values (?, ?, ?, ?, 'b', 'ACTIVE', 0, 0)", id, p[5], "small " + id, "small-" + id);
                jdbc.update("insert into product.product_sku (skuproduct_id, product_id, color_id, discount, sold, version) values (?, ?, ?, ?, 0, 0)",
                        id, id, p[3], p[2]);
                jdbc.update("insert into product.product_size (skuproduct_id, size, price, quantity, version) values (?, ?, ?, 5, 0)",
                        id, p[4], new BigDecimal((String) p[1]));
            }
            // 진행 중인 기획전 둘 — 상품 지정(50% · 40%)과 SALE 카테고리 전체 20%
            jdbc.update("insert into content.event (event_id, title, slug, status) values (9301, 'e1', 'e1', 'ACTIVE'), (9302, 'e2', 'e2', 'ACTIVE')");
            jdbc.update("insert into content.event_product (event_id, product_id, discount_rate) values (9301, ?, 50), (9301, ?, 40)", P_EVENT50, P_BOTH);
            jdbc.update("insert into content.event_category (event_id, category_id, discount_rate) values (9302, ?, 20)", SALE);
            jdbc.execute("vacuum analyze product.product");
        }
        eventPricing.refresh();
    }

    private SearchResultDto search(long category, String low, String high, String color, String size) {
        Params params = new Params();
        params.setCategory(String.valueOf(category));
        params.setPage(1);
        params.setPageSize(10);
        params.setIncludeFilters(false);
        params.setLowPrice(low == null ? null : new BigDecimal(low));
        params.setHighPrice(high == null ? null : new BigDecimal(high));
        params.setColor(color);
        params.setSize(size);
        return searchService.searchProducts(params, SearchEngine.POSTGRES);
    }

    private static List<Long> ids(SearchResultDto r) {
        return r.getProduct().getContent().stream().map(ProductDto::getId).map(Long::valueOf).sorted().toList();
    }

    @Test
    @DisplayName("🔴 7만 개 카테고리의 가격대 · 색상 · 사이즈 필터가 실패하지 않고 센다 — 예전: 후보를 전부 앱으로 읽어 IN (65,535 한도 · 힙)")
    void filtersOnHugeCategory() {
        SearchResultDto priced = search(BIG, "5", "20", null, null);
        assertThat(priced.getTotalProducts()).isEqualTo(PRODUCTS);
        assertThat(priced.getProduct().getContent()).hasSize(10);

        assertThat(search(BIG, null, null, "^Red", null).getTotalProducts()).isEqualTo(PRODUCTS);
        assertThat(search(BIG, "5", "20", "^red", "^m").getTotalProducts()).as("대소문자 무시").isEqualTo(PRODUCTS);
        assertThat(search(BIG, "11", null, null, null).getTotalProducts()).as("맞는 것이 없으면 0 — 전부가 아니다").isZero();
    }

    @Test
    @DisplayName("🔴 가격은 화면의 할인가 — 상시 할인 · 기획전(상품 · 카테고리 지정)은 높은 쪽")
    void priceIsTheShownPrice() {
        assertThat(ids(search(SMALL, null, "80", null, null))).as("70 · 50 — 정가 85 는 빠진다(예전 버그: 정가로 걸렀다)")
                .containsExactly(P_SKU30, P_EVENT50);
        assertThat(ids(search(SMALL, "75", null, null, null))).as("85 — 상시 30% 의 70 · 기획전 50% 의 50 은 빠진다").containsExactly(P_PLAIN);
        assertThat(ids(search(SMALL, null, "60", null, null))).as("상품 기획전 50% 가 상시 10% 보다 크다").containsExactly(P_EVENT50);

        assertThat(ids(search(SALE, null, "60", null, null))).as("상품 40% 와 카테고리 20% 가 겹치면 40% → 60").containsExactly(P_BOTH);
        assertThat(ids(search(SALE, "61", "80", null, null))).as("카테고리 20% → 80").containsExactly(P_CATEGORY);
        assertThat(ids(search(SALE, "60", "60", null, null))).as("경계는 포함").containsExactly(P_BOTH);
        assertThat(search(SALE, "81", null, null, null).getTotalProducts()).as("정가 100 이어도 할인가로 — 0건").isZero();
    }

    @Test
    @DisplayName("🔴 색상 · 사이즈는 앞부분 일치 · 글자 그대로 — M 이 XL 을, .* 가 아무거나를 고르지 않는다")
    void colorAndSizeArePrefixLiterals() {
        assertThat(ids(search(SMALL, null, null, "^navy", null))).containsExactly(P_SKU30);
        assertThat(ids(search(SMALL, null, null, null, "^M"))).containsExactly(P_SKU30, P_EVENT50);
        assertThat(ids(search(SMALL, null, null, null, "^L|^XL"))).as("L 은 XL 을 고르지 않는다 — XL 만").containsExactly(P_PLAIN);
        assertThat(ids(search(SALE, null, null, null, "^l"))).containsExactly(P_BOTH, P_CATEGORY);
        assertThat(search(SMALL, null, null, "^.*", null).getTotalProducts()).isZero();
        assertThat(ids(search(SMALL, null, "60", "^Red", "^M"))).as("가격 · 색상 · 사이즈가 같은 SKU 줄에서").containsExactly(P_EVENT50);
    }
}
