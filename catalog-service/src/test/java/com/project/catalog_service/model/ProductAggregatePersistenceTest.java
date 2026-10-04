package com.project.catalog_service.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 🔴 상품 집합(상세 · Q&A)이 실제 PostgreSQL 에 제대로 저장된다.
 *
 * <p>🐛 예전: ① Q&A cascade 가 MERGE 뿐이라 새 상품의 Q&A 가 조용히 빠졌다. ② 저장된 상품의 상세를
 * {@code setDetails(new HashSet<>())} 로 갈아 끼우면 flush 가 "orphan deletion 컬렉션" 예외로 거절됐다 — 변형 등록이 늘 500.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("상품 집합 저장 — 상세 · Q&A (실제 PostgreSQL)")
class ProductAggregatePersistenceTest {

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

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    private Long persistProduct(int details, int questions) {
        return new TransactionTemplate(txManager).execute(s -> {
            String key = UUID.randomUUID().toString().substring(0, 8);
            Category category = Category.builder().categoryName("agg-" + key).slug("agg-" + key).build();
            em.persist(category);
            Product product = Product.builder().name("Agg " + key).slug("agg-" + key).brand("b")
                    .category(category).build();
            java.util.List<ProductDetails> ds = new java.util.ArrayList<>();
            for (int i = 0; i < details; i++) {
                ds.add(ProductDetails.builder().name("n" + i).value("v" + i).product(product).build());
            }
            java.util.List<ProductQA> qs = new java.util.ArrayList<>();
            for (int i = 0; i < questions; i++) {
                qs.add(ProductQA.builder().question("q" + i).answer("a" + i).product(product).build());
            }
            product.replaceDetails(ds);
            product.replaceQuestions(qs);
            em.persist(product);
            return product.getProductId();
        });
    }

    private long count(String entity, Long productId) {
        return new TransactionTemplate(txManager).execute(s -> em.createQuery(
                "select count(x) from " + entity + " x where x.product.productId = :id", Long.class)
                .setParameter("id", productId).getSingleResult());
    }

    @Test
    @DisplayName("🔴 새 상품의 Q&A · 상세가 함께 저장된다(Q&A cascade 에 PERSIST)")
    void newProductKeepsQuestionsAndDetails() {
        Long id = persistProduct(2, 1);

        assertThat(count("ProductQA", id)).isEqualTo(1);
        assertThat(count("ProductDetails", id)).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 저장된 상품의 상세를 바꿔 넣어도 flush 가 거절되지 않는다 — 빠진 행은 지워지고 새 행이 들어간다")
    void existingProductReplacesDetailsInPlace() {
        Long id = persistProduct(1, 0);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Product product = em.find(Product.class, id);
            product.replaceDetails(List.of(
                    ProductDetails.builder().name("x").value("1").product(product).build(),
                    ProductDetails.builder().name("y").value("2").product(product).build(),
                    ProductDetails.builder().name("z").value("3").product(product).build()));
        });

        assertThat(count("ProductDetails", id)).isEqualTo(3);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("공지 본문은 text 열에 글 그대로 저장된다 — 대형 객체(OID)를 거치지 않는다")
    void noticeBodyIsStoredAsPlainText() {
        long largeObjectsBefore = jdbc.queryForObject("select count(*) from pg_largeobject_metadata", Long.class);
        String body = "점검 안내 — 10월 5일 02:00~04:00 결제가 잠시 멈춥니다.";
        Long id = new TransactionTemplate(txManager).execute(s -> {
            Notice n = Notice.builder().title("점검").body(body).build();
            em.persist(n);
            return n.getNoticeId();
        });

        String raw = jdbc.queryForObject("select body from content.notice where notice_id = ?", String.class, id);
        long largeObjectsAfter = jdbc.queryForObject("select count(*) from pg_largeobject_metadata", Long.class);
        assertThat(raw).as("열에 OID 번호가 아니라 글이 있어야 다른 도구 · SQL 로 읽을 수 있다").isEqualTo(body);
        assertThat(largeObjectsAfter).as("저장마다 대형 객체가 생기면 지워지지 않고 쌓인다").isEqualTo(largeObjectsBefore);
        String read = new TransactionTemplate(txManager).execute(s -> em.find(Notice.class, id).getBody());
        assertThat(read).isEqualTo(body);
    }
}
