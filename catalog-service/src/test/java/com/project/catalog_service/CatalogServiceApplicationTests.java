package com.project.catalog_service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * catalog-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>catalog 는 이 저장소에서 가장 까다로운 기동 경로였다. 손으로 띄워 보고서야
 * <b>커스텀 EMF 가 ddl-auto 를 전달받지 못해 빈 DB 에서 스키마가 아예 생성되지 않는</b> 문제
 * (docs/14 B-8)가 드러났고, 멱등성 엔티티가 스캔 대상 밖에 있는 문제(B-9)도 함께 나왔다.
 * 둘 다 "빈 데이터베이스에 처음 붙일 때만" 나타나므로, 컨테이너로 매번 새 DB 를 만들어 확인한다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("catalog-service 기동 (Testcontainers)")
class CatalogServiceApplicationTests {

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
	private ApplicationContext context;

	@Autowired
	private com.project.catalog_service.repository.jpa.data.ProductSkuRepository productSkuRepository;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	/**
	 * 🐛 <b>카테고리를 고르면 /api/search 가 500 이었다.</b> 색상·사이즈 메뉴의 네이티브 SQL 이
	 * {@code unnest(CAST(:productIds AS BIGINT[]))} 였는데 Hibernate 가 목록을 {@code (?, ?)} 로
	 * 펼쳐 PostgreSQL 이 "cannot cast type record to bigint[]" 로 거절했다. 빈 목록은 통과하므로
	 * 빈 DB 의 기동 테스트는 잡지 못했다 — 그래서 <b>비어 있지 않은 목록</b>으로 실행해 본다.
	 * 데이터는 필요 없다: SQL 이 실행되기만 하면 된다(예전 SQL 은 파싱 단계에서 죽었다).
	 */
	@Test
	@DisplayName("색상·사이즈 메뉴 SQL 이 비어 있지 않은 id 목록으로 실행된다 (PostgreSQL)")
	void colorAndSizeMenuQueriesRunWithIds() {
		assertThat(productSkuRepository.findColorsByProductId(java.util.List.of(1L, 2L))).isEmpty();
		assertThat(productSkuRepository.findSizesByProductId(java.util.List.of(1L))).isEmpty();
		assertThat(productSkuRepository.findAllColors()).isEmpty();
		assertThat(productSkuRepository.findAllSizes()).isEmpty();
	}

	@Test
	@DisplayName("B-8: 빈 DB 에서도 product 스키마 테이블이 생성된다")
	void schemaIsCreatedOnEmptyDatabase() throws Exception {
		// 커스텀 EMF 가 ddl-auto 를 전달받지 못하던 시절에는 여기서 테이블이 0개였고,
		// 기동은 'relation "product.category" does not exist' 로 실패했다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'product'")) {
			rs.next();
			assertThat(rs.getInt(1)).as("빈 DB 에서 스키마가 생성되어야 한다").isPositive();
		}
	}

	@Test
	@DisplayName("B-9: 멱등성 원장 테이블(processed_event)이 존재한다")
	void idempotencyLedgerExists() throws Exception {
		// 이 테이블이 없으면 재고 이중 차감 방지가 조용히 사라진다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables "
								+ "WHERE table_schema = 'product' AND table_name = 'processed_event'")) {
			rs.next();
			assertThat(rs.getInt(1)).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("B-25: Spring Batch 메타데이터 테이블이 생성된다")
	void batchMetadataSchemaIsCreated() throws Exception {
		// 이 테이블들이 없으면 **모든 배치 잡이 즉시 실패**한다 — ES 색인 잡도, CSV 상품 임포트도.
		//   ERROR: relation "meta.batch_job_instance" does not exist
		// 예전에는 스키마를 만드는 곳이 아무 데도 없어 신규 환경에서 항상 이 상태였다(docs/16 §7).
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables "
								+ "WHERE table_schema = 'meta' AND table_name LIKE 'batch_%'")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("Spring Batch 메타 테이블 6종이 있어야 한다")
					.isEqualTo(6);
		}
	}

	@Test
	@DisplayName("JobRepository 가 메타데이터 테이블을 실제로 읽는다")
	void jobRepositoryCanQueryMetadata() {
		// 테이블 존재만으로는 부족하다 — Spring Batch 가 **설정된 접두사(meta.BATCH_)로**
		// 그 테이블에 실제로 접근하는지 확인한다. 잡을 실행하지는 않는다: 색인 잡은 Elasticsearch 를
		// 요구하고 이 서비스의 ES 타임아웃이 600초라 테스트가 멈춰 버린다.
		var explorer = context.getBean(org.springframework.batch.core.explore.JobExplorer.class);

		// 아직 실행 이력이 없어도 예외 없이 조회돼야 한다. 접두사나 스키마가 어긋나 있으면
		// 여기서 'relation "meta.batch_job_instance" does not exist' 로 터진다.
		assertThat(explorer.getJobNames()).isNotNull();
		assertThat(explorer.findJobInstancesByJobName("productImportJob", 0, 10)).isNotNull();
	}
}
