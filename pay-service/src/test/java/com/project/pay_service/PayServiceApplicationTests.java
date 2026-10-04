package com.project.pay_service;

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
 * pay-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>이 저장소에서 가장 비싼 버그들은 하나같이 <b>기동해야만 드러나는</b> 종류였다
 * (docs/14 B-1~B-14, docs/16). 예전에는 인프라가 없어 항상 실패하는 테스트라 꺼 둘 수밖에
 * 없었지만, 이제 컨테이너를 붙여 그 검증을 빌드가 대신 한다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("pay-service 기동 (Testcontainers)")
class PayServiceApplicationTests {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
					.withDatabaseName("paydb")
					.withUsername("product")
					.withPassword("testpw");

	// 빈 문자열 비밀번호는 Redisson 이 AUTH 를 보내다 실패하는 함정이라(docs/14 B-6)
	// 운영 설정과 같은 형태로 비밀번호를 준다.
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
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
		registry.add("spring.data.redis.password", () -> "testpw");
		registry.add("spring.data.redis.ssl.enabled", () -> false);
		registry.add("eureka.client.enabled", () -> false);
		registry.add("eureka.client.register-with-eureka", () -> false);
		registry.add("eureka.client.fetch-registry", () -> false);
		registry.add("management.tracing.enabled", () -> false);
		registry.add("server.ssl.enabled", () -> false);
	}

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("엔티티가 쓰는 'pay' 스키마에 테이블이 생성된다")
	void schemaTablesAreCreated() throws Exception {
		// chat·coupon 은 @Table(schema=...) 를 쓰면서 스키마를 만드는 곳이 없어, DDL 이 WARN 만
		// 남기고 실패한 채 "떠 있는" 상태였다(docs/14 B-13). 테이블 존재를 명시적으로 못 박는다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'pay'")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("'pay' 스키마에 테이블이 하나도 없으면 DDL 이 조용히 실패한 것이다")
					.isPositive();
		}
	}

	@Test
	@DisplayName("결제를 추적 id 로 찾는 조회가 인덱스를 쓴다(V7) — 예전에는 tracking_id 로 시작하는 인덱스가 없어 전체를 읽었다")
	void trackingIdLookupUsesAnIndex() throws Exception {
		try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement()) {
			st.execute("SET enable_seqscan = off");   // 빈 표에서도 쓸 수 있는 인덱스가 있는지만 본다
			StringBuilder plan = new StringBuilder();
			try (var rs = st.executeQuery("EXPLAIN SELECT * FROM pay.payment WHERE tracking_id = 'x'")) {
				while (rs.next()) {
					plan.append(rs.getString(1)).append('\n');
				}
			}
			assertThat(plan.toString()).contains("idx_payment_tracking");
			try (var rs = st.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = 'pay' AND indexname = 'idx_payment'")) {
				rs.next();
				assertThat(rs.getInt(1)).as("유니크 제약과 겹치던 인덱스는 지웠다").isZero();
			}
		}
	}
}
