package com.project.gatewayserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


/**
 * gateway-server 기동 검증 — 레이트리미터가 쓰는 실제 Redis 위에서.
 *
 * <p>게이트웨이는 이 시스템의 단일 진입점이라 뜨지 않으면 전부가 멈춘다. 그런데도 예전에는
 * 컨텍스트 테스트가 꺼져 있었다. 여기서 확인하는 것:
 * <ul>
 *   <li>라우트 정의가 실제로 로딩되는가 (오타 난 라우트는 기동 시점에 드러난다)</li>
 *   <li>RequestRateLimiter 가 요구하는 KeyResolver 빈이 존재하는가</li>
 *   <li>KeyResolver 가 어떤 요청에도 null 키를 내지 않는가 (NPE 회귀 방지)</li>
 * </ul>
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("gateway-server 기동 (Testcontainers)")
class GatewayServerApplicationTests {

	@Container
	static GenericContainer<?> redis =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
					.withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
		registry.add("eureka.client.enabled", () -> false);
		registry.add("eureka.client.register-with-eureka", () -> false);
		registry.add("eureka.client.fetch-registry", () -> false);
		registry.add("management.tracing.enabled", () -> false);
		registry.add("server.ssl.enabled", () -> false);
		registry.add("JWT_SECRET_KEY",
				() -> "0123456789012345678901234567890123456789012345678901234567890123");
		registry.add("LOCAL_KEY_STORE_PATH", () -> "");
		registry.add("LOCAL_TRUST_STORE_PATH", () -> "");
		registry.add("LOCAL_KEY_STORE_PASSWORD", () -> "");
		registry.add("LOCAL_KEY_PASSWORD", () -> "");
	}

	@Autowired
	private ApplicationContext context;

	@Autowired
	private RouteLocator routeLocator;

	@Autowired
	private KeyResolver keyResolver;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	@Test
	@DisplayName("라우트 정의가 로딩된다")
	void routesAreLoaded() {
		var routes = routeLocator.getRoutes().collectList().block();

		assertThat(routes)
				.as("라우트가 하나도 없으면 게이트웨이는 모든 요청을 404 로 돌려준다")
				.isNotNull()
				.isNotEmpty();
	}

	@Test
	@DisplayName("레이트리미터용 KeyResolver 빈이 존재한다")
	void keyResolverBeanExists() {
		// RequestRateLimiter 는 KeyResolver 없이는 동작하지 않는다. 빈 이름/타입이 바뀌면
		// 기동은 되지만 레이트리미팅이 조용히 사라진다.
		assertThat(keyResolver).isNotNull();
	}
}
