package com.project.userservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * B-119: 관리 포트 분리(docs/57 §25) — user-service 는 Spring Security 의 {@code EndpointRequest} 로 프로브 · 지표를 연다.
 * k8s 와 같이 관리 포트를 따로 두어도 그 규칙이 관리 포트에 걸려 <b>인증 없이</b> 열리는지, 앱 포트에는 /actuator 가 없는지,
 * 업무 API 는 그대로 401 인지 실제 PostgreSQL · Redis 위에서 본다.
 *
 * <p>(TLS 를 물려받는 것은 게이트웨이 · config-server 의 ManagementPortTest 가 본다 — 여기서는 TLS 를 끈다.)
 *
 * <p>[JA] 管理ポートの分離 — EndpointRequest の規則が管理ポートでも効き、認証なしで開くか。アプリのポートに /actuator はない。
 */
@Tag("containers")
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@DisplayName("B-119: user-service 관리 포트 — EndpointRequest 규칙 · 앱 포트에 /actuator 없음 (Testcontainers)")
class ManagementPortTest {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
					.withDatabaseName("userdb")
					.withUsername("product")
					.withPassword("testpw");

	// 빈 문자열 비밀번호는 Redisson 이 AUTH 를 보내다 실패하는 함정이라(docs/14 B-6)
	// 운영 설정과 같은 형태로 비밀번호를 준다.
	// [JA] 空文字列のパスワードは Redisson が AUTH を送って失敗する罠なので(docs/14 B-6)
	//      運用設定と同じ形でパスワードを与える。
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
		// [JA] user-service はメール · S3 · JWT の設定を環境変数の placeholder として要求する。
		//      このテストの関心は「コンテキストが起動するか」なので、ダミー値を入れる。
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
		// [JA] TLS はこのテストでは使わないが、設定クラス群がパスの placeholder を要求する。
		registry.add("LOCAL_CACERT_PATH", () -> "");
		registry.add("LOCAL_CRT_PATH", () -> "");
		registry.add("LOCAL_KEY_PATH", () -> "");
		registry.add("LOCAL_KEY_STORE_PATH", () -> "");
		registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
		registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
		registry.add("LOCAL_KEY_PASSWORD", () -> "");
	}

	@Autowired
	private TestRestTemplate rest;

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	private HttpStatus status(int p, String path) {
		return HttpStatus.valueOf(rest.getForEntity("http://localhost:" + p + path, String.class).getStatusCode().value());
	}

	@Test
	@DisplayName("🔴 관리 포트 — 프로브 · 지표가 인증 없이 200 (EndpointRequest 규칙이 관리 포트에도)")
	void managementPortOpensProbesWithoutAuthentication() {
		assertThat(managementPort).isNotEqualTo(port);
		for (String path : new String[] {"/actuator/health/readiness", "/actuator/health/liveness", "/actuator/prometheus"}) {
			assertThat(status(managementPort, path)).as(path).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	@DisplayName("🔴 앱 포트에는 /actuator 가 없다 · 업무 API 는 그대로 401")
	void appPortHasNoActuator() {
		for (String path : new String[] {"/actuator/health/readiness", "/actuator/prometheus"}) {
			assertThat(status(port, path)).as("앱 포트 " + path).isNotEqualTo(HttpStatus.OK);
		}
		assertThat(status(port, "/api/user/me")).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
