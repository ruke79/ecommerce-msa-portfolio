package com.project.catalog_service.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
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

import com.project.catalog_service.batch.config.ElasticsearchBatchConfig;
import com.project.catalog_service.batch.reader.KeysetItemReader;
import com.project.catalog_service.document.ProductDocument;
import com.project.catalog_service.mapper.ProductDocumentMapper;
import com.project.catalog_service.model.Product;

import jakarta.persistence.EntityManagerFactory;

/**
 * 재색인의 상품 리더 — 수백만 개에서도 페이지만큼만 메모리에 올리고, 한 번씩 · id 순으로 · 스레드 안전하게 읽는다(docs/19 §5.2).
 *
 * <p>🐛 예전 리더(JpaPagingItemReader + 컬렉션 JOIN FETCH)는 Hibernate 가 페이지를 메모리에서 잘라 <b>첫 페이지에 상품 전부를</b>
 * 읽었다. 아래 "한 페이지만" 테스트는 그 리더로 되돌리면 실패한다.
 */
// [JA] 再索引の商品リーダー — 数百万件でもページ分だけをメモリに載せ、1 回ずつ · id 順 · スレッド安全に読む。
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("재색인 상품 리더 — id 키셋 페이징")
class KeysetProductReaderTest {

    private static final int PAGE = 5;
    private static final long BASE = 9_000_000L;
    private static final int PRODUCTS = 23;

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
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> true);
        registry.add("chunk.size", () -> PAGE);
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
    private ElasticsearchBatchConfig config;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // 상품 23개(그중 하나는 DELETED) — 각 SKU 2개 · SKU 마다 사이즈 2개 · 소분류 1개
        jdbc.update("DELETE FROM product.product_size WHERE size_id >= ?", BASE);
        jdbc.update("DELETE FROM product.product_sku WHERE skuproduct_id >= ?", BASE);
        jdbc.update("DELETE FROM product.product_subcategory WHERE proc_subcategory_id >= ?", BASE);
        jdbc.update("DELETE FROM product.product WHERE product_id >= ?", BASE);
        jdbc.update("DELETE FROM product.subcategory WHERE subcategory_id = ?", BASE);
        jdbc.update("DELETE FROM product.category WHERE category_id = ?", BASE);
        jdbc.update("DELETE FROM product.product_color WHERE color_id IN (?, ?)", BASE, BASE + 1);

        jdbc.update("INSERT INTO product.category (category_id, category_name, slug, version) VALUES (?, 'toys & games', 'toys-games', 0)", BASE);
        jdbc.update("INSERT INTO product.subcategory (subcategory_id, subcategory_name, slug, category_id, version) VALUES (?, 'board games', 'board-games', ?, 0)", BASE, BASE);
        jdbc.update("INSERT INTO product.product_color (color_id, color, version) VALUES (?, 'red', 0), (?, 'blue', 0)", BASE, BASE + 1);
        long sku = BASE, size = BASE;
        for (int i = 0; i < PRODUCTS; i++) {
            long pid = BASE + i;
            jdbc.update("INSERT INTO product.product (product_id, name, slug, category_id, rating, shipping, status, version) "
                    + "VALUES (?, ?, ?, ?, 4.5, 0, ?, 0)", pid, "Game " + i, "game-" + pid, BASE, i == 7 ? "DELETED" : "ACTIVE");
            jdbc.update("INSERT INTO product.product_subcategory (proc_subcategory_id, product_id, subcategory_id, version) VALUES (?, ?, ?, 0)", pid, pid, BASE);
            for (int c = 0; c < 2; c++) {
                jdbc.update("INSERT INTO product.product_sku (skuproduct_id, sku, discount, sold, color_id, product_id, version) "
                        + "VALUES (?, ?, 0, 0, ?, ?, 0)", sku, "TOY-" + sku, BASE + c, pid);
                for (String s : new String[] { "S", "M" }) {
                    jdbc.update("INSERT INTO product.product_size (size_id, size, quantity, price, skuproduct_id, sku_code, version) "
                            + "VALUES (?, ?, 3, 12.50, ?, ?, 0)", size++, s, sku, "TOY-" + sku + "-" + s);
                }
                sku++;
            }
        }
    }

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    private KeysetItemReader<Product> openReader() {
        KeysetItemReader<Product> reader = config.productReader();
        reader.open(new ExecutionContext());
        return reader;
    }

    @Test
    @DisplayName("🔴 첫 read() 는 한 페이지만 읽는다 — 결과 전체를 메모리에 올리지 않는다")
    void firstReadLoadsOnePage() {
        KeysetItemReader<Product> reader = openReader();
        stats().clear();

        reader.read();

        long loaded = stats().getEntityStatistics(Product.class.getName()).getLoadCount();
        assertThat(loaded).as("첫 페이지에 상품 %d개를 읽었다 — 페이지(%d)보다 많으면 페이징이 메모리에서 일어나고 있다", loaded, PAGE)
                .isLessThanOrEqualTo(PAGE);
        reader.close();
    }

    @Test
    @DisplayName("🔴 모든 상품을 id 순으로 한 번씩 읽고, DELETED 는 뺀다")
    void readsEveryProductOnceInIdOrder() {
        KeysetItemReader<Product> reader = openReader();
        List<Long> ids = new ArrayList<>();
        for (Product p; (p = reader.read()) != null; ) {
            ids.add(p.getProductId());
        }
        reader.close();

        List<Long> ours = ids.stream().filter(id -> id >= BASE).toList();
        assertThat(ids).isSorted().doesNotHaveDuplicates();
        assertThat(ours).hasSize(PRODUCTS - 1).doesNotContain(BASE + 7);
    }

    @Test
    @DisplayName("🔴 넘겨받은 상품은 분리된 상태로도 매퍼가 쓰는 연관이 다 채워져 있다(다른 스레드의 프로세서가 읽는다)")
    void detachedProductsCarryWhatTheMapperReads() {
        KeysetItemReader<Product> reader = openReader();
        Product p;
        do {
            p = reader.read();
        } while (p != null && p.getProductId() < BASE);
        reader.close();
        assertThat(p).isNotNull();

        ProductDocument doc = ProductDocumentMapper.toDocument(p);   // 세션 없이 — LazyInitializationException 이면 실패

        assertThat(doc.getCategory()).isEqualTo("toys & games");
        assertThat(doc.getSubcategories()).extracting(ProductDocument.ProductSubcategoryDocument::getName).containsExactly("board games");
        assertThat(doc.getSkus()).hasSize(2).allSatisfy(s -> assertThat(s.getPrice()).isEqualTo(12.5f));
        assertThat(doc.getSkus()).extracting(ProductDocument.ProductSkuDocument::getColor).containsExactlyInAnyOrder("red", "blue");
    }

    @Test
    @DisplayName("🔴 여러 스레드가 함께 읽어도 같은 상품을 두 번 주지 않는다(스텝이 멀티 스레드다)")
    void concurrentReadersDoNotDuplicate() throws Exception {
        KeysetItemReader<Product> reader = openReader();
        List<Long> seen = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                for (Product p; (p = reader.read()) != null; ) {
                    seen.add(p.getProductId());
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        reader.close();

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen.stream().filter(id -> id >= BASE)).hasSize(PRODUCTS - 1);
    }
}
