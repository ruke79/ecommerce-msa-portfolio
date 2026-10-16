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

    @Autowired
    private com.project.catalog_service.repository.jpa.data.ProductRepository productRepository;

    /** 🐛 삭제한 상품의 slug 를 findBySlug 는 못 보지만 유니크 인덱스(V6)는 본다 — 같은 이름으로 다시 등록하면 500 이었다. */
    @Test
    @DisplayName("🔴 slugTaken 은 삭제(DELETED)된 상품의 slug 도 쓴 것으로 본다 — findBySlug 와 다르게")
    void slugTakenSeesDeletedProducts() {
        Long id = persistProduct(0, 0);
        String slug = new TransactionTemplate(txManager).execute(s -> {
            Product p = em.find(Product.class, id);
            em.createNativeQuery("update product.product set status = 'DELETED' where product_id = :id")
                    .setParameter("id", id).executeUpdate();
            return p.getSlug();
        });

        assertThat(productRepository.findBySlug(slug)).isEmpty();
        assertThat(productRepository.slugTaken(slug)).isTrue();
        assertThat(productRepository.slugTaken("never-used-" + id)).isFalse();
    }

    @Test
    @DisplayName("🔴 검색 필터의 사이즈 · 색상 정규식은 PostgreSQL 에서 글자 그대로 앞부분만 맞춘다 — 메타문자로 500 이 나지 않는다")
    void searchFilterRegexIsLiteralInPostgres() {
        java.util.function.BiFunction<String, String, Boolean> matches = (value, raw) -> (Boolean) em
                .createNativeQuery("select cast(:v as text) ~* cast(:re as text)")
                .setParameter("v", value)
                .setParameter("re", com.project.catalog_service.service.SearchServiceFilters.prefixRegex(raw))
                .getSingleResult();

        assertThat(matches.apply("XL", "^XL|^M")).isTrue();
        assertThat(matches.apply("m", "^XL|^M")).as("대소문자 무시").isTrue();
        assertThat(matches.apply("XL", "^L")).as("앞부분 일치 — L 이 XL 을 고르지 않는다").isFalse();
        assertThat(matches.apply("(", "(")).as("열린 괄호도 글자 — 예전에는 invalid regular expression").isTrue();
        assertThat(matches.apply("Navy", ".*")).as(".* 는 아무거나가 아니다").isFalse();
        assertThat(matches.apply("A+B", "^A+B")).isTrue();
        assertThat(matches.apply("AAB", "^A+B")).isFalse();
        assertThat(matches.apply("검정 (무광)", "^검정 (무광)")).isTrue();
    }

    @Autowired
    private com.project.catalog_service.repository.jpa.data.ProductSkuRepository skuRepository;

    @Test
    @DisplayName("🔴 예약의 잠금 대기 상한(SET LOCAL lock_timeout)은 실제로 기다림을 끊고, 트랜잭션이 끝나면 풀린다")
    void localLockTimeoutBoundsTheWait() throws Exception {
        Long id = persistProduct(0, 0);
        String lockRow = "select product_id from product.product where product_id = :id for update";
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createNativeQuery(lockRow).setParameter("id", id).getResultList();
            locked.countDown();
            try {
                done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        try {
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                skuRepository.setLocalLockTimeout("300ms");
                em.createNativeQuery(lockRow).setParameter("id", id).getResultList();
            })).satisfies(e -> assertThat(com.project.catalog_service.grpc.StockReservationGrpcServiceAccess.isLockWait(e))
                    .as("lock_timeout(55P03) 로 끊겼다 — gRPC 가 DEADLINE_EXCEEDED 로 돌려줄 예외: " + e).isTrue());
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(3));
        } finally {
            done.countDown();
            holder.join(10_000);
        }

        String after = new TransactionTemplate(txManager).execute(s ->
                (String) em.createNativeQuery("select current_setting('lock_timeout')").getSingleResult());
        assertThat(after).as("SET LOCAL — 커넥션 풀로 돌아간 뒤 다른 요청에 남지 않는다").isEqualTo("0");
    }
}
