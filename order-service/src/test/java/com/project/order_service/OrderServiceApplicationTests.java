package com.project.order_service;

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
 * order-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>예전에는 인프라가 없으면 항상 실패하는 {@code @SpringBootTest} 였고, 그래서 {@code @Disabled}
 * 로 꺼 둘 수밖에 없었다. 문제는 이 저장소에서 가장 비싼 버그들이 하나같이 <b>기동해야만 드러나는</b>
 * 종류였다는 점이다(docs/14 B-1~B-9, docs/16). 컨테이너를 붙여 그 검증을 빌드가 대신 하게 한다.
 *
 * <p>Kafka 는 붙이지 않는다 — 컨슈머는 브로커가 없어도 재시도 로그만 남기고 기동을 막지 않으므로
 * 컨텍스트 로딩 검증에는 불필요하고 테스트만 느려진다. 사가 흐름은 별도 통합 테스트의 몫이다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("order-service 기동 (Testcontainers)")
class OrderServiceApplicationTests {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
					.withDatabaseName("orderdb")
					.withUsername("product")
					.withPassword("testpw");

	// 비밀번호를 설정한다. 빈 문자열 비밀번호는 Redisson 이 AUTH 를 보내다 실패하는
	// 알려진 함정이라(docs/14 B-6) 운영 설정과 같은 형태로 맞춘다.
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
		// 이 테스트 범위 밖의 외부 의존성은 끈다.
		registry.add("eureka.client.enabled", () -> false);
		registry.add("eureka.client.register-with-eureka", () -> false);
		registry.add("eureka.client.fetch-registry", () -> false);
		registry.add("management.tracing.enabled", () -> false);
		registry.add("server.ssl.enabled", () -> false);
		// PayPal 검증은 자격증명이 없으면 **기동을 거부한다**(PaypalConfig — 의도된 fail closed).
		// 테스트에는 시크릿이 없으므로 명시적으로 끈다. 이 한 줄이 필요하다는 사실 자체가
		// 기본값이 안전한 쪽으로 잡혀 있다는 증거다(docs/35).
		registry.add("paypal.verification.mode", () -> "disabled");
	}

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("Flyway 마이그레이션이 적용되어 아웃박스 파티션이 생성된다")
	void outboxPartitionsAreCreated() throws Exception {
		// docs/09 P0 의 파티션 마이그레이션이 실제로 적용됐는지 확인한다.
		// 파티션 부모 + 월 파티션 + DEFAULT 파티션이 만들어져야 한다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM pg_tables "
								+ "WHERE schemaname = 'orders' AND tablename LIKE 'payment_outbox_event%'")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("파티션 부모 + 월 파티션들이 생성되어야 한다")
					.isGreaterThan(1);
		}
	}
}
