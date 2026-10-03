package com.project.userservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * user-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>이 저장소에서 가장 비싼 버그들은 하나같이 <b>기동해야만 드러나는</b> 종류였다
 * (docs/14 B-1~B-14, docs/16). 예전에는 인프라가 없어 항상 실패하는 테스트라 꺼 둘 수밖에
 * 없었지만, 이제 컨테이너를 붙여 그 검증을 빌드가 대신 한다.
 */
@Tag("containers")
@Testcontainers
// @SpringBootTest 는 기본적으로 메트릭 내보내기를 끈다. 그러면 /actuator/prometheus 엔드포인트가
// 아예 등록되지 않아 "보안 때문에 401" 인지 "엔드포인트가 없어서" 인지 구분할 수 없다.
// 운영과 같은 조건으로 검증하려면 관측성 자동설정을 켜야 한다.
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("user-service 기동 (Testcontainers)")
class UserServiceApplicationTests {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
					.withDatabaseName("userdb")
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
		// user-service 는 메일·S3·JWT 설정을 환경변수 placeholder 로 요구한다.
		// 이 테스트의 관심사는 "컨텍스트가 뜨는가" 이므로 더미 값을 넣는다.
		registry.add("MAIL_HOST", () -> "localhost");
		registry.add("MAIL_PORT", () -> "25");
		registry.add("MAIL_USERNAME", () -> "dummy");
		registry.add("MAIL_PASSWORD", () -> "dummy");
		registry.add("AMAZON_ACCESS_KEY", () -> "dummy");
		registry.add("AMAZON_SECRET_KEY", () -> "dummy");
		registry.add("AMAZON_BUCKET_NAME", () -> "dummy");
		registry.add("AMAZON_REGION", () -> "ap-northeast-2");
		registry.add("JWT_SECRET_KEY", () -> "0123456789012345678901234567890123456789012345678901234567890123");
		// TLS 는 이 테스트에서 쓰지 않지만, 설정 클래스들이 경로 placeholder 를 요구한다.
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
	private TestRestTemplate rest;

	@LocalServerPort
	private int port;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("엔티티가 쓰는 'users' 스키마에 테이블이 생성된다")
	void schemaTablesAreCreated() throws Exception {
		// chat·coupon 은 @Table(schema=...) 를 쓰면서 스키마를 만드는 곳이 없어, DDL 이 WARN 만
		// 남기고 실패한 채 "떠 있는" 상태였다(docs/14 B-13). 테이블 존재를 명시적으로 못 박는다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'users'")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("'users' 스키마에 테이블이 하나도 없으면 DDL 이 조용히 실패한 것이다")
					.isPositive();
		}
	}

	@Test
	@DisplayName("B-11: 액추에이터 프로브는 인증에 막히지 않아야 한다")
	void actuatorProbesAreReachableWithoutAuthentication() {
		// user-service 만 Spring Security 를 쓰는데 .anyRequest().authenticated() 뿐이라
		// /actuator/health/readiness 가 401 을 돌려줬다. 그 상태로 배포하면
		//   - k8s 는 파드를 영원히 NotReady 로 두고(트래픽 미전달)
		//   - Prometheus 는 이 서비스만 스크레이프하지 못한다.
		// 핵심은 "인증에 막히지 않는가" 이다. 종합 health 는 의존성 상태에 따라 503 일 수 있고
		// 그건 인증 문제가 아니므로 401/403 만 실패로 본다.
		for (String path : new String[] {"/actuator/health", "/actuator/health/readiness",
				"/actuator/health/liveness", "/actuator/prometheus"}) {
			ResponseEntity<String> response = rest.getForEntity("http://localhost:" + port + path, String.class);
			assertThat(response.getStatusCode())
					.as("%s 가 인증에 막히면 k8s 프로브와 Prometheus 가 실패한다(docs/14 B-11)", path)
					.isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
		}
	}

	@Test
	@DisplayName("프로브와 메트릭 엔드포인트는 200 을 돌려준다")
	void probeEndpointsReturnOk() {
		// liveness/readiness 는 의존성과 무관하게 컨테이너 상태만 보므로 200 이어야 한다.
		for (String path : new String[] {"/actuator/health/readiness", "/actuator/health/liveness",
				"/actuator/prometheus"}) {
			assertThat(rest.getForEntity("http://localhost:" + port + path, String.class).getStatusCode())
					.as("%s", path)
					.isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	@DisplayName("보호된 API 는 여전히 401 이어야 한다 (액추에이터 개방이 전체 개방이 되면 안 된다)")
	void protectedEndpointsRemainSecured() {
		ResponseEntity<String> response =
				rest.getForEntity("http://localhost:" + port + "/api/user/me", String.class);

		assertThat(response.getStatusCode())
				.as("액추에이터를 열었다고 업무 API 까지 열리면 안 된다")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Autowired
	private com.project.userservice.repository.jpa.PasswordResetTokenRepository passwordResetTokenRepository;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Test
	@DisplayName("🔴 만료된 재설정 토큰 정리가 실제 DB 에서 돈다 — 예전엔 Date 를 Instant 필드에 묶어 매번 실패했다(경고 로그뿐)")
	void expiredResetTokenPurgeRunsOnPostgres() {
		new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
				passwordResetTokenRepository.deleteAllExpiredSince(java.time.Instant.now()));
	}

	@Autowired
	private javax.sql.DataSource dataSource;

	@Autowired
	private com.project.userservice.service.AddressService addressService;

	@Test
	@DisplayName("🔴 기본 배송지를 바꿔도 '사용자당 기본 하나' 유니크 인덱스에 걸리지 않는다(실제 PostgreSQL)")
	void switchingDefaultAddressRespectsPartialUniqueIndex() {
		var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
		jdbc.update("INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked,"
				+ " credentials_non_expired, email_verified, enabled, is_two_factor_enabled)"
				+ " VALUES (9001, 'addr-switch', 'addr-switch@example.com', true, true, true, true, true, false)");
		jdbc.update("INSERT INTO users.shipping_address (shipping_address_id, user_id, version, active, address1, city, state, zip_code)"
				+ " VALUES (9101, 9001, 0, true, 'addr-1', 'Seoul', 'Seoul', '01234'), (9102, 9001, 0, false, 'addr-2', 'Busan', 'Busan', '05678')");

		var selected = addressService.selectShipAddress("addr-switch", "9102");

		org.assertj.core.api.Assertions.assertThat(selected).isNotNull();
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForList(
				"SELECT shipping_address_id FROM users.shipping_address WHERE user_id = 9001 AND active", Long.class))
				.containsExactly(9102L);
	}

	@Test
	@DisplayName("🔴 2,000자 한국어 리뷰가 저장된다 — 예전 idx_review(rating, review, size, fit)는 B-tree 행 한도(약 2.7KB)를 넘어 거절했다")
	void longKoreanReviewIsStored() {
		var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
		jdbc.update("INSERT INTO users.users (user_id, username, email, account_non_expired, account_non_locked,"
				+ " credentials_non_expired, email_verified, enabled, is_two_factor_enabled)"
				+ " VALUES (9002, 'long-review', 'long-review@example.com', true, true, true, true, true, false)");
		jdbc.update("INSERT INTO users.review_style (rstyle_id, version, color, image) VALUES (9201, 0, 'black', 'img')");
		// 무작위 음절 — 압축으로 줄어들지 않는 실제 문장에 가깝게(같은 글자 반복은 압축돼 한도를 넘지 않는다).
		var random = new java.util.Random(42);
		var text = new StringBuilder();
		for (int i = 0; i < 2000; i++) {
			text.append((char) (0xAC00 + random.nextInt(11172)));
		}
		jdbc.update("INSERT INTO users.review (review_id, version, rating, product_id, rstyle_id, user_id, review, size, fit)"
				+ " VALUES (9301, 0, 5, 1, 9201, 9002, ?, 'L', 'Large')", text.toString());
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
				"SELECT char_length(review) FROM users.review WHERE review_id = 9301", Integer.class)).isEqualTo(2000);
	}
}
