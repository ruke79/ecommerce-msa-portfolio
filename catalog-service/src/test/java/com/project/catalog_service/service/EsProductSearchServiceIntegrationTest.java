package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.catalog_service.document.ProductDocument;

/**
 * Elasticsearch 검색 경로 통합 테스트.
 *
 * <p>이 경로는 <b>한 번도 동작한 적이 없었다.</b> 실제 ES 를 붙여 보고서야 독립적인 결함 4건이
 * 순서대로 드러났다(docs/16 §5):
 * <ul>
 *   <li>B-25 Spring Batch 메타 스키마 미생성 → 색인 잡 자체가 못 돈다</li>
 *   <li>B-26 es-settings.json 의 중복 {@code settings} 래퍼 → 인덱스 생성 실패</li>
 *   <li>B-26 리더의 {@code MultipleBagFetchException} → 색인 스텝 매번 실패</li>
 *   <li>B-27 {@code Page} 캐시 역직렬화 불가 → 두 번째 검색부터 500</li>
 * </ul>
 *
 * <p>여기서는 색인 배치 전체가 아니라 <b>검색 서비스가 인덱스를 제대로 조회하는지</b>를 고정한다.
 * 배치 경로는 B-25 가 설계 결정으로 남아 있어 별도 과제다.
 *
 * <p>⚠️ nori/kuromoji 플러그인은 배포처가 막혀 설치할 수 없어, 이 테스트는 기본 애널라이저로
 * <b>배선(색인→질의→결과)</b>만 검증한다. 한국어 형태소 분석 품질은 별도 검증이 필요하다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest(properties = "search.engine=es")
@DisplayName("Elasticsearch 검색 (Testcontainers)")
class EsProductSearchServiceIntegrationTest {

    private static final String ALIAS = "product-alias";
    private static final String INDEX = "products-test";

    @Container
    static ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(DockerImageName.parse("elasticsearch:8.15.5")
                    .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

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
        registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
        registry.add("spring.elasticsearch.username", () -> "");
        registry.add("spring.elasticsearch.password", () -> "");
        registry.add("spring.elasticsearch.ssl.bundle", () -> "");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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

        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        // 이 테스트의 관심사 밖인 외부 자격증명은 더미로 채운다.
        registry.add("AMAZON_ACCESS_KEY", () -> "dummy");
        registry.add("AMAZON_SECRET_KEY", () -> "dummy");
        registry.add("AMAZON_BUCKET_NAME", () -> "dummy");
        registry.add("AMAZON_REGION", () -> "ap-northeast-2");
        registry.add("LOCAL_ELASTICSEARCH_USERNAME", () -> "");
        registry.add("LOCAL_ELASTICSEARCH_PASSWORD", () -> "");
        registry.add("LOCAL_CACERT_PATH", () -> "");
        registry.add("LOCAL_CRT_PATH", () -> "");
        registry.add("LOCAL_KEY_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PATH", () -> "");
        registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
        registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
        registry.add("LOCAL_KEY_PASSWORD", () -> "");
    }

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private EsProductSearchService searchService;

    @BeforeEach
    void indexSampleProducts() {
        var indexOps = elasticsearchOperations.indexOps(IndexCoordinates.of(INDEX));
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.create();

        elasticsearchOperations.save(List.of(
                product("200", "Winter Padding Jacket", "NorthPeak"),
                product("201", "Summer T-Shirt", "CoolWear")),
                IndexCoordinates.of(INDEX));

        elasticsearchOperations.indexOps(IndexCoordinates.of(INDEX)).refresh();
        // 검색 서비스는 배치가 롤오버하는 alias 를 조회한다. 테스트 인덱스에 alias 를 건다.
        if (!elasticsearchOperations.indexOps(IndexCoordinates.of(ALIAS)).exists()) {
            elasticsearchOperations.indexOps(IndexCoordinates.of(INDEX))
                    .alias(new org.springframework.data.elasticsearch.core.index.AliasActions()
                            .add(new org.springframework.data.elasticsearch.core.index.AliasAction.Add(
                                    org.springframework.data.elasticsearch.core.index.AliasActionParameters.builder()
                                            .withIndices(INDEX).withAliases(ALIAS).build())));
        }
    }

    private ProductDocument product(String id, String name, String brand) {
        // ProductDocument 는 @Builder 만 제공한다(세터 없음).
        return ProductDocument.builder()
                .productId(id)
                .name(name)
                .brand(brand)
                .description(name)
                .build();
    }

    @Test
    @DisplayName("색인된 상품을 이름으로 검색할 수 있다")
    void searchFindsIndexedProduct() {
        List<Long> ids = searchService.searchProductIds("Padding", 10);

        assertThat(ids).contains(200L);
    }

    @Test
    @DisplayName("일치하는 상품이 없으면 빈 결과를 돌려준다 (예외가 아니라)")
    void searchReturnsEmptyWhenNothingMatches() {
        assertThat(searchService.searchProductIds("존재하지않는상품명xyz", 10)).isEmpty();
    }

    @Test
    @DisplayName("limit 을 넘는 결과를 돌려주지 않는다")
    void searchRespectsLimit() {
        assertThat(searchService.searchProductIds("Winter Summer", 1)).hasSizeLessThanOrEqualTo(1);
    }
}
