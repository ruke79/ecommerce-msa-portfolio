package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;


import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

/**
 * 상품 목록 조회가 발행하는 <b>SQL 개수</b>를 고정한다 — N+1 회귀 방지.
 *
 * <p>왜 개수인가: 응답 시간은 하드웨어·데이터량·캐시 상태에 좌우돼 CI 에서 재현되지 않는다.
 * 반면 <b>쿼리 개수는 환경과 무관한 구조적 사실</b>이다. 상품 20건을 읽는 데 SQL 이 1번인지
 * 81번인지는 노트북에서도 운영에서도 같다. 그래서 성능 회귀 중 가장 흔하고 가장 비싼
 * N+1 만큼은 빌드가 지킬 수 있다.
 *
 * <p>배경: {@code Product} 는 LAZY 컬렉션을 4개(subcategories·details·questions·skus) 갖고,
 * {@code ProductMapper.toDto} 가 그 넷을 모두 순회한다. 배치 페치가 없으면
 * <b>상품 N건 → 1 + 4N 쿼리</b>가 된다. 20건이면 81번. 코드에는 {@code //@BatchSize} 가
 * 주석 처리된 채 남아 있었다 — 누군가 문제를 알았지만 켜지 않았다는 흔적이다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("상품 조회 SQL 개수 (N+1 가드)")
class ProductQueryCountTest {

    /** 목록 한 페이지 크기. 실제 API 기본값과 같은 규모로 잡는다. */
    private static final int PRODUCTS = 20;

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

        // 통계 수집을 켜야 쿼리 개수를 셀 수 있다.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> true);

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

    // catalog 는 @Primary 로 선언된 기본 EMF 하나를 쓴다(batch/config/DataSourceConfig).
    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ProductService productService;

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("상품 20건 조회가 상품당 별도 쿼리를 내지 않는다")
    void listingDoesNotIssueOneQueryPerProduct() {
        stats().clear();

        productService.getProducts();

        long queries = stats().getPrepareStatementCount();
        // 상한의 근거: 목록 1 + LAZY 컬렉션 4종을 배치 페치로 각 1~2회.
        // 배치 페치가 꺼지면 1 + 4×N 이 되어 20건에서 81 을 넘는다 — 그때 이 테스트가 깨진다.
        // 상한을 20(=상품 수)으로 잡아, "상품당 최소 한 번"이라는 N+1 의 정의에 걸리게 한다.
        assertThat(queries)
                .as("""
                    상품 %d건 조회에 SQL %d회. 상품 수에 비례해 늘고 있다면 N+1 이다 —
                    Product 의 LAZY 컬렉션(subcategories·details·questions·skus)에
                    @BatchSize 가 붙어 있는지 확인할 것.
                    """.formatted(PRODUCTS, queries))
                .isLessThan(PRODUCTS);
    }

    @Test
    @DisplayName("연관 컬렉션에 배치 페치가 켜져 있다")
    void lazyCollectionsDeclareBatchFetching() {
        // 위 테스트는 데이터가 적으면 우연히 통과할 수 있다. 이 검사는 데이터와 무관하게
        // **선언 자체**를 본다 — 주석 처리된 @BatchSize 가 다시 꺼지면 여기서 잡힌다.
        var product = emf.getMetamodel().entity(com.project.catalog_service.model.Product.class);
        assertThat(product).isNotNull();

        for (String field : new String[] { "subcategories", "details", "questions", "skus" }) {
            java.lang.reflect.Field f;
            try {
                f = com.project.catalog_service.model.Product.class.getDeclaredField(field);
            } catch (NoSuchFieldException e) {
                throw new AssertionError("Product." + field + " 이 없어졌습니다 — 테스트를 갱신하세요", e);
            }
            assertThat(f.getAnnotation(org.hibernate.annotations.BatchSize.class))
                    .as("Product.%s 는 LAZY 컬렉션이고 매퍼가 전부 순회한다. "
                            + "@BatchSize 가 없으면 상품 N건 조회가 4N 개의 추가 쿼리를 낸다.", field)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("단건 조회도 컬렉션마다 별도 왕복을 하지 않는다")
    void singleProductFetchIsBounded() {
        stats().clear();
        productService.getProducts();
        long first = stats().getPrepareStatementCount();

        // 같은 호출을 반복해도 쿼리 수가 늘지 않아야 한다(영속성 컨텍스트/캐시 무관하게 상한 유지).
        stats().clear();
        productService.getProducts();
        long second = stats().getPrepareStatementCount();

        assertThat(second)
                .as("반복 조회에서 쿼리 수가 증가하면 캐시나 페치 전략이 의도대로 동작하지 않는 것이다")
                .isLessThanOrEqualTo(first);
    }

    @Test
    @DisplayName("색상은 여러 SKU 가 공유할 수 있어야 한다 (@OneToOne 회귀 방지)")
    void colourMayBeSharedAcrossSkus() {
        // ProductSku.color 가 @OneToOne 이면 JPA 가 color_id 에 UNIQUE 를 걸어
        // **색상 행 하나를 오직 한 SKU 만** 참조할 수 있게 된다. 그러면
        //   · 카탈로그 전체 SKU 수가 색상 행 수로 상한이 걸리고
        //   · 색상 필터 패싯(frontend/ColorsFilter.jsx)이 무의미해진다 — 색상당 상품 1개.
        // 시드가 SKU 509 = 색상 509 로 제약을 정확히 채우고 있어, 상품을 하나만 더 넣어도
        // 적재가 막혔다(docs/19 §5.1). 매핑과 스키마 양쪽을 검사한다.
        java.lang.reflect.Field colour;
        try {
            colour = com.project.catalog_service.model.ProductSku.class.getDeclaredField("color");
        } catch (NoSuchFieldException e) {
            throw new AssertionError("ProductSku.color 가 없어졌습니다 — 테스트를 갱신하세요", e);
        }
        assertThat(colour.getAnnotation(jakarta.persistence.OneToOne.class))
                .as("색상은 공유 어휘다(findColorsByProductId 가 DISTINCT 를 쓰고, "
                        + "프론트엔드가 색상 필터를 제공한다). @OneToOne 은 공유를 막는다.")
                .isNull();
        assertThat(colour.getAnnotation(jakarta.persistence.ManyToOne.class))
                .as("여러 SKU 가 한 색상을 참조할 수 있어야 한다")
                .isNotNull();

        // 선언만으로는 부족하다 — 기존 DB 에 남아 있는 UNIQUE 제약도 확인한다.
        // (Hibernate 의 ddl-auto: update 는 제약을 **지우지 않는다**. V4 마이그레이션이 지운다.)
        Long uniques = (Long) em.createNativeQuery("""
                SELECT count(*) FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                WHERE nsp.nspname = 'product' AND rel.relname = 'product_sku'
                  AND con.contype = 'u'
                  AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                        WHERE attrelid = rel.oid AND attname = 'color_id' AND NOT attisdropped)]::smallint[]
                """).getSingleResult();
        assertThat(uniques)
                .as("product_sku(color_id) 의 UNIQUE 제약이 남아 있으면 색상 공유가 여전히 막힌다 "
                        + "— V4 마이그레이션이 적용됐는지 확인할 것")
                .isZero();
    }
}
