package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
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

import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

/**
 * 재고 표시의 합친 질의({@code ProductSizeRepository#liveAndHeld}) — 실제 PostgreSQL(Flyway 로 올린 스키마 · 시드)에서.
 *
 * <p>지키는 것: ① 예전 두 질의(사이즈별 지금 수량 · 살아 있는 예약 합)를 합친 것과 같은 값 — 만료된 · 확정된 예약은 빠진다. ② 목록 한 페이지의
 * 재고 반영이 SQL 하나다(예전 둘 — 2026-10-24 프로파일에서 catalog CPU 의 20%, docs/83 §5). UNION ALL 의 열 형(bigint · varchar)이 맞는지는
 * 실제 DB 에서만 드러난다.
 *
 * <p>[JA] 在庫表示の合わせた照会を実際の PostgreSQL で — 以前の二つの照会と同じ値で、一覧一ページの在庫反映が SQL 一つ。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("재고 표시 — 지금 수량 · 예약 합을 한 질의로(실제 PostgreSQL)")
class StockAvailabilityQueryTest {

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

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ProductSizeRepository sizes;

    @Autowired
    private StockAvailability availability;

    /** 상품 셋(색상 하나 · 사이즈 M · L) — 한 번만 넣는다. 각 상품의 첫 옵션 {@code [product_id, color_id, size]}. */
    private static List<Object[]> seeded;

    private static synchronized List<Object[]> options() {
        if (seeded != null) {
            return seeded;
        }
        List<Object[]> out = new ArrayList<>();
        try (var c = postgres.createConnection(""); var st = c.createStatement()) {
            st.execute("insert into product.category (category_name, slug) values ('재고', 'stock-test')");
            st.execute("insert into product.product_color (color) values ('#123456')");
            for (int i = 1; i <= 3; i++) {
                st.execute("insert into product.product (rating, category_id, name, slug) select 4.5, max(category_id), 'p" + i
                        + "', 'stock-p" + i + "' from product.category");
                st.execute("insert into product.product_sku (discount, sold, product_id, color_id, sku) select 0, 0, max(p.product_id),"
                        + " max(c.color_id), 'STK-" + i + "' from product.product p, product.product_color c");
                st.execute("insert into product.product_size (quantity, skuproduct_id, size, price) select 7, max(skuproduct_id), 'M', 10"
                        + " from product.product_sku");
                st.execute("insert into product.product_size (quantity, skuproduct_id, size, price) select 3, max(skuproduct_id), 'L', 10"
                        + " from product.product_sku");
                try (var rs = st.executeQuery("select k.product_id, k.color_id from product.product_sku k order by k.skuproduct_id desc limit 1")) {
                    rs.next();
                    out.add(new Object[] {rs.getLong(1), rs.getLong(2), "M"});
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        seeded = out;
        return out;
    }

    /** 예약 행 — 앱의 트랜잭션과 상관없이 바로 넣는다(데이터소스가 둘이라 트랜잭션 관리자도 여럿이다). */
    private void reserve(long orderId, Object[] option, int qty, String status, Instant expiresAt) {
        try (var c = postgres.createConnection(""); var ps = c.prepareStatement("""
                insert into product.stock_reservation (order_id, product_id, color_id, size, qty, status, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setLong(1, orderId);
            ps.setLong(2, ((Number) option[0]).longValue());
            ps.setLong(3, ((Number) option[1]).longValue());
            ps.setString(4, (String) option[2]);
            ps.setInt(5, qty);
            ps.setString(6, status);
            ps.setTimestamp(7, java.sql.Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 예전 두 질의 그대로 — 대조 기준. */
    @SuppressWarnings("unchecked")
    private Map<String, Long> legacy(List<Long> ids, Instant now) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                select s.size_id, s.quantity from product.product_size s
                  join product.product_sku k on k.skuproduct_id = s.skuproduct_id where k.product_id in (:ids)
                """).setParameter("ids", ids).getResultList()) {
            out.put("L:" + r[0], ((Number) r[1]).longValue());
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                select r.product_id, r.color_id, r.size, sum(r.qty) from product.stock_reservation r
                 where r.product_id in (:ids) and r.status = 'HELD' and r.expires_at > :now
                 group by r.product_id, r.color_id, r.size
                """).setParameter("ids", ids).setParameter("now", now).getResultList()) {
            out.put("H:" + r[0] + ":" + r[1] + ":" + r[2], ((Number) r[3]).longValue());
        }
        return out;
    }

    private static Map<String, Long> combined(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            String key = "L".equals(String.valueOf(r[0])) ? "L:" + r[1] : "H:" + r[2] + ":" + r[3] + ":" + r[4];
            assertThat(out.put(key, ((Number) r[5]).longValue())).as("키가 겹치지 않는다: " + key).isNull();
        }
        return out;
    }

    @Test
    @DisplayName("🔴 합친 질의 = 예전 두 질의 — 살아 있는 예약만(만료 · 확정은 빠진다), 같은 옵션은 합친다")
    void sameAsTheTwoQueries() {
        List<Object[]> options = options();
        assertThat(options).hasSize(3);
        Instant now = Instant.now();
        reserve(910001, options.get(0), 2, "HELD", now.plus(30, ChronoUnit.MINUTES));
        reserve(910002, options.get(0), 1, "HELD", now.plus(10, ChronoUnit.MINUTES));
        reserve(910003, options.get(0), 5, "HELD", now.minus(1, ChronoUnit.MINUTES));       // 만료
        reserve(910004, options.get(1), 4, "COMMITTED", now.plus(30, ChronoUnit.MINUTES));  // 확정
        List<Long> ids = new ArrayList<>();
        for (Object[] o : options) {
            ids.add(((Number) o[0]).longValue());
        }

        Map<String, Long> now2 = combined(sizes.liveAndHeld(ids, now));

        assertThat(now2).isEqualTo(legacy(ids, now));
        assertThat(now2).as("살아 있는 두 예약만 합쳐서")
                .containsEntry("H:" + options.get(0)[0] + ":" + options.get(0)[1] + ":" + options.get(0)[2], 3L);
        assertThat(now2.keySet().stream().filter(k -> k.startsWith("L:"))).as("사이즈마다 지금 수량 — 상품 셋 × 둘").hasSize(6);
        assertThat(now2).as("만료 · 확정 예약만 있는 옵션은 없다")
                .doesNotContainKey("H:" + options.get(1)[0] + ":" + options.get(1)[1] + ":" + options.get(1)[2]);
    }

    @Test
    @DisplayName("🔴 목록 한 페이지의 재고 반영은 SQL 하나 — 예전에는 둘(docs/83 §5)")
    void onePageIsOneStatement() {
        List<Long> ids = new ArrayList<>();
        for (Object[] o : options()) {
            ids.add(((Number) o[0]).longValue());
        }
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        availability.snapshot(ids, true);

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }
}
