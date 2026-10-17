package com.project.catalog_service.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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

import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.service.HomeSectionService;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 이미지 저장소(S3)가 없는 데모 클러스터에 상품을 넣는 길 — {@code ops/44 PLACEHOLDER_IMAGE=…}(generate-catalog-data.py
 * {@code --placeholder-image}).
 *
 * <p>🐛 예전: 시드 배치 잡은 S3 쓰기 점검에서 멈추고(데모 Secret 은 {@code AMAZON_*=demo-not-used}), 생성기는
 * {@code product_color} 가 비어 멈췄다 — 데모 shop.lab 에는 상품을 넣을 길이 없었다(2026-10-17). 이제 색상을 시드 CSV 에서
 * 넣고 SKU 이미지를 자리 표시 주소로 채운다.
 *
 * <p>Flyway 로 올린 실제 카탈로그 스키마에 생성기를 돌리고, 운영의 {@link HomeSectionService}(V10 의 홈 섹션 · Hibernate
 * validate)가 첫 화면을 그리는지 본다 — 손으로 적은 기대값이 아니라 화면이 받는 응답으로.
 *
 * <p>[JA] S3 のないデモクラスターに商品を入れる道。以前はシードバッチも生成器も止まり、shop.lab に商品を入れられなかった。
 * 生成器を本物のスキーマに流し、本番の HomeSectionService がトップ画面を描けるかを見る。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("데모 카탈로그 — 시드 배치 없이 자리 표시 이미지로 적재 (ops/44 PLACEHOLDER_IMAGE)")
class DemoCatalogSeedIntegrationTest {

    private static final String PLACEHOLDER = "/logo512.png";
    private static final int PRODUCTS = 300;
    private static final long OFFSET = 1_000_000L;

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
        // 데모 Secret 과 같다 — 이 값으로는 시드 배치 잡이 시작하지 못한다
        registry.add("AMAZON_ACCESS_KEY", () -> "demo-not-used");
        registry.add("AMAZON_SECRET_KEY", () -> "demo-not-used");
        registry.add("AMAZON_BUCKET_NAME", () -> "demo-not-used");
        registry.add("AMAZON_REGION", () -> "ap-northeast-2");
    }

    @Autowired
    HomeSectionService homeSections;

    private record Run(int code, String out) {}

    @BeforeEach
    void emptyCatalog() throws Exception {
        assumeTrue(psycopg2(), "python3 의 psycopg2 가 없다(pip install psycopg2-binary)");
        sql("TRUNCATE product.product_size, product.product_sku, product.product_subcategory, product.product, "
                + "product.product_color CASCADE");
    }

    // ── 실행 도우미 ────────────────────────────────────────────────────────

    /** ops/44 의 load 가 부르는 것과 같은 생성기 명령 — DSN 만 컨테이너로. */
    private static Run generate(String... extra) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python3", repoRoot().resolve("ops/tools/generate-catalog-data.py").toString(),
                "--products", String.valueOf(PRODUCTS), "--id-offset", String.valueOf(OFFSET),
                "--chunk", "100", "--resume", "--yes",
                "--copy", "host=" + postgres.getHost() + " port=" + postgres.getMappedPort(5432)
                        + " dbname=productdb user=product sslmode=disable"));
        cmd.addAll(List.of(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", "testpw");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    private static boolean psycopg2() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import psycopg2").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static long count(String query) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void sql(String statements) throws Exception {
        try (Connection c = db(); Statement s = c.createStatement()) {
            s.execute(statements);
        }
    }

    private static Connection db() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 색상이 비어 있어도 시드 CSV 색상 + 자리 표시 이미지로 적재되고, 홈(V10 섹션)이 그 상품을 그린다")
    void loadsWithoutSeedBatchAndHomeRenders() throws Exception {
        Run r = generate("--placeholder-image", PLACEHOLDER);
        assertThat(r.code()).as(r.out()).isZero();

        assertThat(count("SELECT count(*) FROM product.product_color")).as("시드 CSV 의 색상").isEqualTo(509);
        assertThat(count("SELECT count(*) FROM product.product_color WHERE colorimage = '" + PLACEHOLDER + "'")).isEqualTo(509);
        assertThat(count("SELECT count(*) FROM product.product WHERE product_id > " + OFFSET)).isEqualTo(PRODUCTS);
        assertThat(count("SELECT count(*) FROM product.product_sku WHERE images::text[] <> ARRAY['" + PLACEHOLDER + "']::text[]"))
                .as("모든 SKU 이미지가 자리 표시 한 장 — 클래스패스 경로(images\\…)가 아니다").isZero();

        HomeView home = homeSections.home();
        assertThat(home.sections()).as("V10 의 홈 섹션(fashion · books …)이 상품을 찾는다").isNotEmpty();
        List<ProductDto> shown = home.sections().stream().map(HomeSectionView::products).flatMap(List::stream).toList();
        assertThat(shown).isNotEmpty();
        assertThat(shown).allSatisfy(p -> {
            ProductSkuDto sku = p.getSkus().get(0);
            assertThat(sku.getImages()).containsExactly(PLACEHOLDER);
            assertThat(sku.getSizes()).as("가격 · 수량이 있는 사이즈 — 장바구니에 담을 수 있다").isNotEmpty();
            assertThat(sku.getColor().getColorImage()).isEqualTo(PLACEHOLDER);
        });

        // 다시 실행하면 이어서(이미 다 들어갔다) — 색상을 두 번 넣지 않는다
        Run again = generate("--placeholder-image", PLACEHOLDER);
        assertThat(again.code()).as(again.out()).isZero();
        assertThat(count("SELECT count(*) FROM product.product_color")).isEqualTo(509);
        assertThat(count("SELECT count(*) FROM product.product WHERE product_id > " + OFFSET)).isEqualTo(PRODUCTS);
    }

    @Test
    @DisplayName("자리 표시 없이 빈 카탈로그면 예전처럼 멈추고 --placeholder-image 를 알려 준다 — 아무것도 넣지 않는다")
    void withoutPlaceholderStopsAndPointsToTheOption() throws Exception {
        Run r = generate();
        assertThat(r.code()).isNotZero();
        assertThat(r.out()).contains("product_color 가 비어 있다").contains("--placeholder-image");
        assertThat(count("SELECT count(*) FROM product.product")).isZero();
        assertThat(count("SELECT count(*) FROM product.product_color")).isZero();
    }
}
