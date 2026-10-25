package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 상품 이름 검색은 NFKC 로 비교한다 — 실제 PostgreSQL(Flyway V23 의 {@code product.search_key} · 식 인덱스).
 *
 * <p>🐛 예전: {@code lower(name) LIKE lower('%검색어%')} — 일본어 IME 가 내는 전각 영숫자 · 반각 카나로 친 검색어는 반각 · 전각으로
 * 저장된 상품과 글자가 달라 하나도 맞지 않았다. 이름은 그대로 두고 비교에만 NFKC 를 건다.
 *
 * <p>[JA] 商品名の検索は NFKC で比較する — 全角英数 · 半角カナの検索語が原文のまま保存された名前を見つける(名前は変えない)。
 */
@Tag("containers")
@SpringBootTest
@org.testcontainers.junit.jupiter.Testcontainers
@DisplayName("상품 이름 검색 — 전각 · 반각을 NFKC 로 (V23)")
class SearchNameNfkcTest {

    private static final long CATEGORY = 9500L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("productdb").withUsername("product").withPassword("testpw");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

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
    private ObjectMapper mapper;

    @BeforeEach
    void seed() {
        Integer seeded = jdbc.queryForObject("select count(*) from product.product where category_id = ?", Integer.class, CATEGORY);
        if (seeded != null && seeded > 0) {
            return;
        }
        jdbc.update("insert into product.category (category_id, category_name, slug) values (?, 'nfkc', 'nfkc')", CATEGORY);
        Object[][] products = {
                { 9_500_001L, "Ｔシャツ ＢＡＳＩＣ" },   // 전각 영문으로 저장된 이름
                { 9_500_002L, "ｼｬﾂ ﾌﾞﾙｰ" },            // 반각 카나로 저장된 이름
                { 9_500_003L, "Basic Hoodie" },
                { 9_500_004L, "コットン100%ワンピース" },
                { 9_500_005L, "コットン100ワンピース" },
        };
        for (Object[] p : products) {
            jdbc.update("insert into product.product (product_id, category_id, name, slug, brand, status, rating, version) "
                    + "values (?, ?, ?, ?, 'b', 'ACTIVE', 0, 0)", p[0], CATEGORY, p[1], "nfkc-" + p[0]);
        }
        // 다른 카테고리의 상품 2만 개 — 인덱스를 고를 만한 크기(작으면 플래너가 다 훑는다)
        jdbc.update("insert into product.category (category_id, category_name, slug) values (9501, 'filler', 'filler')");
        jdbc.update("""
                insert into product.product (product_id, category_id, name, slug, brand, status, rating, version)
                select 9_600_000 + g, 9501, 'filler item ' || g, 'filler-' || g, 'b', 'ACTIVE', 0, 0 from generate_series(1, 20000) g
                """);
    }

    /** 화면이 보내는 것처럼 — 쿼리 문자열이 setter 로 들어온다(그때 NFKC). */
    private List<String> names(String search) {
        SearchParamsRequest params = mapper.convertValue(Map.of(
                "search", search, "category", String.valueOf(CATEGORY), "page", 1, "pageSize", 20, "includeFilters", false),
                SearchParamsRequest.class);
        SearchResultDto r = searchService.searchProducts(params, SearchEngine.POSTGRES);
        return r.getProduct().getContent().stream().map(ProductDto::getName).sorted().toList();
    }

    @Test
    @DisplayName("🔴 반각 영문 · 전각 카나로 쳐도 전각 영문 · 반각 카나로 저장된 이름이 나온다 — 그 반대도")
    void widthDoesNotMatter() {
        assertThat(names("tシャツ basic")).containsExactly("Ｔシャツ ＢＡＳＩＣ");
        assertThat(names("ＢＡＳＩＣ")).containsExactly("Basic Hoodie", "Ｔシャツ ＢＡＳＩＣ");
        assertThat(names("シャツ")).containsExactly("Ｔシャツ ＢＡＳＩＣ", "ｼｬﾂ ﾌﾞﾙｰ");
        assertThat(names("ﾌﾞﾙｰ")).as("반각 탁점이 합쳐진 글자와 같다").containsExactly("ｼｬﾂ ﾌﾞﾙｰ");
    }

    @Test
    @DisplayName("이름은 원문 그대로 돌려준다 · 검색어의 % 는 글자다(예전에는 % 하나가 전부를 맞혔다)")
    void originalNamesAndLiteralPercent() {
        assertThat(names("１００％")).containsExactly("コットン100%ワンピース");
        assertThat(names("%")).containsExactly("コットン100%ワンピース");
    }

    @Test
    @DisplayName("🔴 V23 식 인덱스를 쓴다 — 함수가 바뀌어 인덱스와 식이 어긋나면 상품 수만큼 훑는다")
    void usesTheSearchKeyIndex() {
        jdbc.execute("vacuum analyze product.product");
        String plan = jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("set enable_seqscan = off");
                StringBuilder out = new StringBuilder();
                try (var rs = st.executeQuery("explain select product_id from product.product "
                        + "where status <> 'DELETED' and product.search_key(name) like '%' || product.search_key('ｼｬﾂ') || '%'")) {
                    while (rs.next()) {
                        out.append(rs.getString(1)).append('\n');
                    }
                }
                st.execute("reset enable_seqscan");
                return out.toString();
            }
        });
        assertThat(plan).contains("idx_product_name_search_key_trgm");
    }
}
