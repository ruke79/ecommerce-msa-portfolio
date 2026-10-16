package com.project.cart_service;

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
 * cart-service 기동 검증 — 실제 PostgreSQL/Redis 컨테이너 위에서.
 *
 * <p>이 저장소에서 가장 비싼 버그들은 하나같이 <b>기동해야만 드러나는</b> 종류였다
 * (docs/14 B-1~B-14, docs/16). 예전에는 인프라가 없어 항상 실패하는 테스트라 꺼 둘 수밖에
 * 없었지만, 이제 컨테이너를 붙여 그 검증을 빌드가 대신 한다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("cart-service 기동 (Testcontainers)")
class CartServiceApplicationTests {

	@Container
	static PostgreSQLContainer<?> postgres =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
					.withDatabaseName("cartdb")
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
		// 운영(application-local.yml)처럼 파티션 잡을 켠다 — 첫 점검은 1시간 뒤라 이 테스트 동안 DB 를 건드리지 않는다.
		registry.add("outbox.partitions.tables", () -> "cart.outbox_event");
		registry.add("outbox.partitions.initial-delay", () -> "PT1H");
	}

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("애플리케이션 컨텍스트가 실제로 뜬다")
	void contextLoads() {
		assertThat(context).isNotNull();
	}

	/**
	 * 🔴 common 을 컴포넌트 스캔하는 이 앱에서도 파티션 잡이 뜬다. 예전에는 AutoConfigurationExcludeFilter 가 빠져 자동 설정이
	 * 스캔으로 먼저 평가돼 통째로 빠졌다 — 잡이 한 번도 돌지 않았다(OutboxPartitionComponentScanTest).
	 * [JA] common をスキャンするこのアプリでもパーティションジョブが起動する。以前は自動設定が丸ごと外れていた。
	 */
	@Test
	@DisplayName("🔴 아웃박스 파티션 잡(Maintainer)과 지표가 뜬다 — common 을 스캔해도 자동 설정이 빠지지 않는다")
	void outboxPartitionJobIsWired() {
		assertThat(context.getBeansOfType(com.project.common.outbox.partition.OutboxPartitionMaintainer.class)).hasSize(1);
		assertThat(context.getBean(com.project.common.outbox.partition.OutboxPartitionMaintainer.class).tables())
				.containsExactly("cart.outbox_event");
	}

	@Test
	@DisplayName("엔티티가 쓰는 'cart' 스키마에 테이블이 생성된다")
	void schemaTablesAreCreated() throws Exception {
		// chat·coupon 은 @Table(schema=...) 를 쓰면서 스키마를 만드는 곳이 없어, DDL 이 WARN 만
		// 남기고 실패한 채 "떠 있는" 상태였다(docs/14 B-13). 테이블 존재를 명시적으로 못 박는다.
		try (var conn = java.sql.DriverManager.getConnection(
						postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement();
				var rs = st.executeQuery(
						"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'cart'")) {
			rs.next();
			assertThat(rs.getInt(1))
					.as("'cart' 스키마에 테이블이 하나도 없으면 DDL 이 조용히 실패한 것이다")
					.isPositive();
		}
	}

	@Autowired
	private com.project.cart_service.repository.jpa.CartRepository cartRepository;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager txManager;

	@Test
	@DisplayName("처음 담기가 동시에 와도 장바구니는 하나 — 늦은 쪽은 앞선 쪽의 커밋을 기다렸다가 같은 장바구니를 읽는다")
	void concurrentFirstAddCreatesOneCart() throws Exception {
		long userId = 4242L;
		var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
		var firstInserted = new java.util.concurrent.CountDownLatch(1);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var a = pool.submit(() -> tx.execute(s -> {
				cartRepository.insertIfAbsent(1L, userId);
				firstInserted.countDown();
				try {
					Thread.sleep(500);   // 커밋 전 — B 의 INSERT 가 이 행에서 기다린다
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return cartRepository.findByUserId(userId).orElseThrow().getCartId();
			}));
			firstInserted.await();
			var b = pool.submit(() -> tx.execute(s -> {
				int created = cartRepository.insertIfAbsent(2L, userId);
				assertThat(created).as("늦은 쪽은 만들지 않는다").isZero();
				return cartRepository.findByUserId(userId).orElseThrow().getCartId();
			}));
			assertThat(a.get()).isEqualTo(1L);
			assertThat(b.get()).as("예전에는 장바구니가 둘 생겨 이 조회가 예외였다").isEqualTo(1L);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("🔴 처리 원장(V6) — 같은 이벤트는 한 번만 1, 두 번째는 0(ON CONFLICT DO NOTHING), 롤백되면 남지 않는다")
	void processedEventLedger() {
		var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
		assertThat(tx.<Integer>execute(s -> cartRepository.markProcessed("cart-empty-9001")).intValue()).isEqualTo(1);
		assertThat(tx.<Integer>execute(s -> cartRepository.markProcessed("cart-empty-9001")).intValue()).isZero();

		tx.executeWithoutResult(s -> {
			cartRepository.markProcessed("cart-empty-9002");
			s.setRollbackOnly();   // 비우기가 실패해 롤백됐다 — 다음 재전달이 다시 처리해야 한다
		});
		assertThat(tx.<Integer>execute(s -> cartRepository.markProcessed("cart-empty-9002")).intValue()).isEqualTo(1);
	}
}
