package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 상품이 65,535 개를 넘는 카테고리의 첫 쪽 검색(필터 메뉴 포함) — 실제 PostgreSQL 에서.
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 실패 53.61%. 필터 메뉴의 색상 · 사이즈 · 상세가 카테고리 상품 id 를
 * 전부 {@code IN (:ids)} 로 펼쳐 {@code PSQLException: PreparedStatement can have at most 65,535 parameters} — fashion · books 같은
 * 큰 카테고리의 첫 쪽은 매번 실패했고(catalog 로그 96건), 브랜드는 DISTINCT 없이 상품마다 한 줄이었다.
 *
 * <p>한 카테고리에 상품 70,000 개(브랜드 7종) + SKU 셋 · 상세 셋을 넣고, 이웃 카테고리에 다른 색상을 둔다 — 메뉴가
 * 그 카테고리 것만 · 중복 없이 나오는지 본다. 예전 코드는 첫 호출에서 예외다.
 *
 * <p>[JA] 商品が 65,535 を超えるカテゴリの 1 ページ目の検索(フィルターメニュー込み)— 本物の PostgreSQL で。以前は IN の展開で例外だった。
 */
@Tag("containers")
@SpringBootTest(properties = "catalog.facets.wait=10s")
@org.testcontainers.junit.jupiter.Testcontainers
@DisplayName("필터 메뉴 — 상품 7만 개 카테고리 (65,535 바인드 변수 한도)")
class SearchFilterFacetsScaleTest {

    private static final long CATEGORY = 9100L;
    private static final long NEIGHBOUR = 9101L;
    private static final int PRODUCTS = 70_000;
    private static final long FIRST_ID = 9_100_000L;

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
    private SearchFilterFacets filterFacets;

    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

    /** SearchParamsRequest 는 protected 기본 생성자만 있다. */
    static class Params extends SearchParamsRequest {
        Params() { super(); }
    }

    @BeforeEach
    void seed() {
        try (var c = redisConnectionFactory.getConnection()) {
            c.serverCommands().flushAll();   // 앞 실행의 캐시 없이
        }
        Integer seeded = jdbc.queryForObject("select count(*) from product.product where category_id = ?", Integer.class, CATEGORY);
        if (seeded != null && seeded == PRODUCTS) {
            return;
        }
        jdbc.update("insert into product.category (category_id, category_name, slug) values (?, 'scale', 'scale'), (?, 'neighbour', 'neighbour')",
                CATEGORY, NEIGHBOUR);
        // 브랜드 7종이 1만 번씩 — 예전 질의는 7만 줄을 읽었다
        jdbc.update("""
                insert into product.product (product_id, category_id, name, slug, brand, status, rating, version)
                select ? + g, ?, 'scale ' || g, 'scale-' || g, 'brand-' || (g % 7), 'ACTIVE', 0, 0
                from generate_series(1, ?) g
                """, FIRST_ID, CATEGORY, PRODUCTS);
        jdbc.update("insert into product.product (product_id, category_id, name, slug, brand, status, rating, version) "
                + "values (?, ?, 'neighbour', 'neighbour-1', 'other-brand', 'ACTIVE', 0, 0)", FIRST_ID + PRODUCTS + 1, NEIGHBOUR);

        jdbc.update("insert into product.product_color (color_id, color, version) values (9101, 'Red', 0), (9102, 'Navy', 0), (9103, 'Green', 0)");
        // SKU 넷: 7만 개 카테고리에 Red · Navy · Red, 이웃 카테고리에 Green — 사이즈 M · L · M · XS
        long[][] skus = { { 9201, FIRST_ID + 1, 9101 }, { 9202, FIRST_ID + 2, 9102 }, { 9203, FIRST_ID + 3, 9101 },
                { 9204, FIRST_ID + PRODUCTS + 1, 9103 } };
        String[] sizes = { "M", "L", "M", "XS" };
        for (int i = 0; i < skus.length; i++) {
            jdbc.update("insert into product.product_sku (skuproduct_id, product_id, color_id, discount, sold, version) values (?, ?, ?, 0, 0, 0)",
                    skus[i][0], skus[i][1], skus[i][2]);
            jdbc.update("insert into product.product_size (skuproduct_id, size, price, quantity, version) values (?, ?, 10, 5, 0)",
                    skus[i][0], sizes[i]);
        }
        jdbc.update("insert into product.product_details (product_id, name, value, version) values "
                + "(?, 'Style', 'Casual', 0), (?, 'Style', 'Casual', 0), (?, 'Material', 'Cotton', 0), (?, 'Style', 'Formal', 0)",
                FIRST_ID + 1, FIRST_ID + 2, FIRST_ID + 3, FIRST_ID + PRODUCTS + 1);
    }

    @Test
    @DisplayName("🔴 첫 쪽 검색이 실패하지 않고 메뉴는 그 카테고리 것만 · 중복 없이 — 예전: 65,535 바인드 변수 한도로 예외")
    void firstPageWithFiltersOnHugeCategory() {
        Params params = new Params();
        params.setCategory(String.valueOf(CATEGORY));
        params.setPage(1);
        params.setPageSize(10);
        params.setIncludeFilters(true);

        // 메뉴는 결과 캐시 밖에서 붙는다(SearchController 와 같은 순서) — 이 테스트는 기다림 상한을 넉넉히(10초) 준다
        SearchResultDto result = filterFacets.attach(searchService.searchProducts(params, SearchEngine.POSTGRES), params);

        assertThat(result.getTotalProducts()).as("개수는 세지 않는다 — 10개 + 더 있음 1").isEqualTo(11);
        assertThat(result.getProduct().getContent()).hasSize(10);
        assertThat(result.getBrandsDB()).as("브랜드는 7종 — 상품마다 한 줄이 아니다")
                .containsExactlyInAnyOrder("brand-0", "brand-1", "brand-2", "brand-3", "brand-4", "brand-5", "brand-6");
        assertThat(result.getColors()).as("이웃 카테고리의 Green 은 없다").containsExactlyInAnyOrder("Red", "Navy");
        assertThat(result.getSizes()).containsExactlyInAnyOrder("M", "L");
        assertThat(result.getDetails()).extracting(ProductDetailsDto::getName, ProductDetailsDto::getValue)
                .containsExactlyInAnyOrder(tuple("Style", "Casual"), tuple("Material", "Cotton"));
    }

    @Test
    @DisplayName("상세 필터를 걸면 상세 표를 조인해 그 상품만 센다 — 걸지 않을 때와 같은 개수 규칙")
    void detailsFilterStillNarrows() {
        Params params = new Params();
        params.setCategory(String.valueOf(CATEGORY));
        params.setPage(1);
        params.setPageSize(10);
        params.setIncludeFilters(false);
        params.setStyle("^Casual");

        SearchResultDto result = searchService.searchProducts(params, SearchEngine.POSTGRES);

        assertThat(result.getTotalProducts()).as("Casual 은 상품 둘").isEqualTo(2);
        assertThat(result.getProduct().getContent()).hasSize(2);
    }
}
