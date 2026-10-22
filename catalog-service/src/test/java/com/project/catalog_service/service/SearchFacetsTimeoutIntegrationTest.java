package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.controller.SearchController;

/**
 * 검색 질의의 시간 상한(503 · 커넥션 반환)과 요청 밖의 필터 메뉴 — 실제 PostgreSQL · Redis.
 *
 * <p>🐛 2026-10-20 실습(상품 1,000만, docs/83 §2.8): 필터 검색 하나가 75 ~ 195초, 메뉴의 사이즈 DISTINCT 가 76초 동안 커넥션을 쥐었다.
 * 게이트웨이는 10초에 포기하지만 catalog 의 스레드와 커넥션은 묶인 채라 풀(파드마다 5)이 차서 home · banners 까지 5xx 였다(넓힘 10.33%,
 * 필터 검색 95.04%). 느린 질의를 만드는 대신 <b>표를 잠가</b> 질의가 끝나지 않게 한다 — 같은 결과(커넥션을 쥔 채 기다림)를 몇 초 안에 재현한다.
 * 이 테스트의 상한: 검색 2초 · 메뉴 질의 3초 · 메뉴 기다림 0.8초.
 *
 * <p>[JA] 検索照会の時間上限(503・接続の返却)とリクエスト外のフィルターメニュー — 実 PostgreSQL・Redis。表をロックして終わらない照会を作る。
 */
@Tag("containers")
@SpringBootTest(properties = {
        "catalog.search.query-timeout-seconds=2",
        "catalog.facets.query-timeout-seconds=3",
        "catalog.facets.wait=800ms" })
@Testcontainers
@DisplayName("검색 시간 상한 · 요청 밖의 필터 메뉴 (실제 PostgreSQL · Redis)")
class SearchFacetsTimeoutIntegrationTest {

    private static final long CATEGORY = 9300L;
    private static final long FIRST_ID = 9_300_000L;

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
    private SearchController controller;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private FacetQueries facetQueries;

    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        try (var c = redisConnectionFactory.getConnection()) {
            c.serverCommands().flushAll();
        }
        Integer seeded = jdbc.queryForObject("select count(*) from product.product where category_id = ?", Integer.class, CATEGORY);
        if (seeded != null && seeded > 0) {
            return;
        }
        jdbc.update("insert into product.category (category_id, category_name, slug) values (?, 'timeout', 'timeout')", CATEGORY);
        jdbc.update("""
                insert into product.product (product_id, category_id, name, slug, brand, status, rating, version)
                select ? + g, ?, 'timeout ' || g, 'timeout-' || g, 'brand-' || (g % 3), 'ACTIVE', 0, 0
                from generate_series(1, 30) g
                """, FIRST_ID, CATEGORY);
        jdbc.update("insert into product.product_color (color_id, color, version) values (9301, 'Red', 0), (9302, 'Navy', 0)");
        jdbc.update("insert into product.product_sku (skuproduct_id, product_id, color_id, discount, sold, version) values "
                + "(9401, ?, 9301, 0, 0, 0), (9402, ?, 9302, 0, 0, 0)", FIRST_ID + 1, FIRST_ID + 2);
        jdbc.update("insert into product.product_size (skuproduct_id, size, price, quantity, version) values "
                + "(9401, 'M', 10, 5, 0), (9402, 'L', 10, 5, 0)");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MvcResult search(boolean includeFilters) throws Exception {
        return mvc().perform(get("/api/search").param("category", String.valueOf(CATEGORY)).param("page", "1")
                .param("pageSize", "10").param("includeFilters", String.valueOf(includeFilters))).andReturn();
    }

    /** 다른 연결에서 표를 잠근다 — 닫으면(롤백) 풀린다. */
    private Connection lock(String table) throws Exception {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
        }
        return c;
    }

    /** 잠금을 기다리는 질의(이 연결 · 잠근 연결 말고) — 시간 상한이 취소했으면 0. */
    private int waitingOnLocks() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock' and pid <> pg_backend_pid()", Integer.class);
    }

    @Test
    @DisplayName("🔴 검색 질의가 상한(2초)에서 끊겨 503 — DB 에서 취소돼 기다리는 질의가 남지 않고, 풀리면 200")
    void searchQueryTimesOutWith503AndFreesTheConnection() throws Exception {
        Connection locked = lock("product.product");
        try {
            long start = System.nanoTime();
            MvcResult r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> search(false),
                    "상한이 없으면 잠금이 풀릴 때까지 기다린다 — 운영에서는 75 ~ 195초");
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            assertThat(ms).as("상한 2초 + 여유").isLessThan(8_000);
            Thread.sleep(500);
            assertThat(waitingOnLocks()).as("취소된 질의가 커넥션을 쥔 채 남지 않는다").isZero();
        } finally {
            locked.close();
        }
        assertThat(search(false).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("🔴 메뉴 질의가 상한(3초)에서 취소된다 — 백그라운드라도 커넥션을 영영 쥐지 않는다")
    void facetQueryHasItsOwnTimeout() throws Exception {
        Connection locked = lock("product.product_size");
        try {
            long start = System.nanoTime();
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                try {
                    facetQueries.sizes(CATEGORY);
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            }, "상한이 없으면 잠금이 풀릴 때까지 — 운영 1,000만 상품의 사이즈 DISTINCT 는 76초");
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(thrown).as("시간 상한 예외").isNotNull();
            assertThat(SearchController.isQueryTimeout(thrown)).as(String.valueOf(thrown)).isTrue();
            assertThat(ms).isLessThan(8_000);
            Thread.sleep(500);
            assertThat(waitingOnLocks()).isZero();
        } finally {
            locked.close();
        }
        assertThat(facetQueries.sizes(CATEGORY)).containsExactlyInAnyOrder("M", "L");
    }

    @Test
    @DisplayName("🔴 메뉴 계산이 오래 걸려도 첫 쪽은 기다림 상한(0.8초) 안에 200 — 그 메뉴만 비고, 계산이 끝나면 다음 요청이 받는다(Redis 왕복)")
    void slowFacetDoesNotHoldTheSearch() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(facetQueries).sizes(CATEGORY);
        try {
            long start = System.nanoTime();
            MvcResult r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> search(true),
                    "메뉴를 요청 안에서 만들면 계산이 끝날 때까지 기다린다");
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(ms).as("기다림 상한 0.8초 + 여유").isLessThan(4_000);
            JsonNode body = json.readTree(r.getResponse().getContentAsString());
            assertThat(body.get("product").get("content")).hasSize(10);
            assertThat(body.get("sizes")).as("늦은 메뉴는 비어서 나간다").isEmpty();
            assertThat(json.convertValue(body.get("colors"), List.class)).as("다른 메뉴는 그대로").containsExactlyInAnyOrder("Red", "Navy");
        } finally {
            release.countDown();
        }
        List<String> sizes = List.of();
        for (int i = 0; i < 40 && sizes.isEmpty(); i++) {
            Thread.sleep(250);
            JsonNode body = json.readTree(search(true).getResponse().getContentAsString());
            sizes = json.convertValue(body.get("sizes"), json.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        assertThat(sizes).as("계산이 끝난 뒤 — Redis 의 FacetEntry 에서").containsExactlyInAnyOrder("M", "L");
        org.mockito.Mockito.verify(facetQueries, org.mockito.Mockito.times(1)).sizes(CATEGORY);
    }
}
