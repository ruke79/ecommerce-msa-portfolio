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
		// 운영(application-local.yml)처럼 파티션 잡을 켠다 — 첫 점검은 1시간 뒤라 이 테스트 동안 DB 를 건드리지 않는다.
		registry.add("outbox.partitions.tables", () -> "orders.payment_outbox_event");
		registry.add("outbox.partitions.initial-delay", () -> "PT1H");
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
				.containsExactly("orders.payment_outbox_event");
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

	@Autowired
	private com.project.order_service.repository.jpa.OrderRepository orders;

	@Autowired
	private com.project.order_service.repository.jpa.OrderAddressRepository addresses;

	@Autowired
	private com.project.order_service.service.OrderDataService orderData;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager txManager;

	/** 조회 화면의 색상 — 부를 때 트랜잭션이 열려 있는지 기록한다(B-87). */
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.project.order_service.client.CatalogServiceClient catalog;

	@Autowired
	private com.project.order_service.service.OrderService orderService;

	private long jdbcLong(String sql) throws Exception {
		try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}

	private void jdbc(String sql) throws Exception {
		try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement()) {
			st.execute(sql);
		}
	}

	private Long newOrderWithAddress() {
		return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> {
			var address = com.project.order_service.model.OrderAddress.builder()
					.firstname("길동").lastname("홍").address1("세종대로 1").city("서울").state("서울")
					.zipCode("04524").country("KR").phoneNumber("010-0000-0000").build();
			addresses.save(address);
			var order = com.project.order_service.model.Order.builder()
					.customerId(5L).trackingId("t-" + java.util.UUID.randomUUID())
					.orderStatus(com.project.common.constants.OrderStatus.NOT_PROCESSED)
					.shippingAddress(address).build();
			return orders.save(order).getOrderId();
		});
	}

	@Test
	@DisplayName("주문은 주소를 한 방향으로만 가리킨다 — 주소의 order_id 는 비고, 세금 열은 기본값 0(V25)")
	void orderPointsToAddressOneWay() throws Exception {
		Long id = newOrderWithAddress();
		long addressId = jdbcLong("select order_address_id from orders.orders where order_id = " + id);
		assertThat(addressId).isPositive();
		assertThat(jdbcLong("select count(*) from orders.order_address where order_address_id = " + addressId + " and order_id is null"))
				.as("새 주소는 주문을 가리키지 않는다(외래 키는 주문 쪽 하나)").isEqualTo(1);
		assertThat(jdbcLong("select tax_price from orders.orders where order_id = " + id)).isZero();
	}

	@Test
	@DisplayName("결제 전 주문 버리기 — 새 모양(주문 → 주소)과 V25 전 모양(주소도 주문을 가리킴) 모두 주문 · 주소가 지워진다")
	void discardRemovesOrderAndAddressInBothShapes() throws Exception {
		Long fresh = newOrderWithAddress();
		long freshAddress = jdbcLong("select order_address_id from orders.orders where order_id = " + fresh);

		Long legacy = newOrderWithAddress();
		long legacyAddress = jdbcLong("select order_address_id from orders.orders where order_id = " + legacy);
		jdbc("update orders.order_address set order_id = " + legacy + " where order_address_id = " + legacyAddress);

		assertThat(orderData.discardUnplaced(fresh)).isTrue();
		assertThat(orderData.discardUnplaced(legacy)).isTrue();

		for (long a : new long[] { freshAddress, legacyAddress }) {
			assertThat(jdbcLong("select count(*) from orders.order_address where order_address_id = " + a)).isZero();
		}
		assertThat(jdbcLong("select count(*) from orders.orders where order_id in (" + fresh + ", " + legacy + ")")).isZero();
	}

	@Test
	@DisplayName("🔴 환불의 행 잠금(lockVersion) — 실제 PostgreSQL 에서 버전을 돌려주고, 다른 트랜잭션은 커밋까지 기다린다")
	void lockVersionLocksTheOrderRow() throws Exception {
		Long id = newOrderWithAddress();
		var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
		java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		Thread holder = new Thread(() -> tx.executeWithoutResult(s -> {
			assertThat(orders.lockVersion(id)).isNotNull();
			locked.countDown();
			try {
				release.await(10, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		holder.start();
		assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement()) {
			st.execute("set lock_timeout = '300ms'");
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> st.executeQuery(
					"select version from orders.orders where order_id = " + id + " for update"))
					.as("두 번째 환불은 첫 번째가 끝날 때까지 기다린다").hasMessageContaining("lock timeout");
		} finally {
			release.countDown();
			holder.join(10_000);
		}
	}

	@Test
	@DisplayName("🔴 결제 대사 질의 — 결제 참조가 붙고 10분 넘게 결제 전인 주문만(갓 기록된 것 · 참조 없는 것 · 결제된 것은 아니다)")
	void unconfirmedPaymentQuery() throws Exception {
		Long stale = newOrderWithAddress();
		Long fresh = newOrderWithAddress();
		Long noRef = newOrderWithAddress();
		Long paid = newOrderWithAddress();
		jdbc("update orders.orders set payment_provider = 'PAYPAL', provider_payment_id = 'cap-" + stale + "', updated_at = now() - interval '30 minutes' where order_id = " + stale);
		jdbc("update orders.orders set payment_provider = 'PAYPAL', provider_payment_id = 'cap-" + fresh + "', updated_at = now() where order_id = " + fresh);
		jdbc("update orders.orders set updated_at = now() - interval '30 minutes' where order_id = " + noRef);
		jdbc("update orders.orders set payment_provider = 'PAYPAL', provider_payment_id = 'cap-" + paid + "', order_status = 'PAID', updated_at = now() - interval '30 minutes' where order_id = " + paid);

		java.time.LocalDateTime before = java.time.LocalDateTime.now().minusMinutes(10);
		var ids = orders.findUnconfirmedPayments(com.project.common.constants.OrderStatus.NOT_PROCESSED, before,
				org.springframework.data.domain.PageRequest.of(0, 100));
		assertThat(ids).contains(stale).doesNotContain(fresh, noRef, paid);
		assertThat(orders.countUnconfirmedPayments(com.project.common.constants.OrderStatus.NOT_PROCESSED, before))
				.isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("🔴 주문 생성 멱등 키(V26) — 같은 고객 · 같은 키는 한 건만, 다른 고객의 같은 키 · 키 없는 주문은 막지 않는다")
	void createKeyIsUniquePerCustomer() throws Exception {
		Long first = newOrderWithAddress();
		Long other = newOrderWithAddress();
		Long noKey1 = newOrderWithAddress();
		Long noKey2 = newOrderWithAddress();
		jdbc("update orders.orders set create_key = 'try-abc-123' where order_id = " + first);
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> jdbc("update orders.orders set create_key = 'try-abc-123' where order_id = " + other))
				.as("같은 고객의 같은 키는 두 번째가 막힌다").hasMessageContaining("uq_orders_customer_create_key");
		jdbc("update orders.orders set customer_id = 6, create_key = 'try-abc-123' where order_id = " + other);
		assertThat(jdbcLong("select count(*) from orders.orders where create_key is null and order_id in (" + noKey1 + ", " + noKey2 + ")"))
				.isEqualTo(2);
		assertThat(orderData.findByCreateKey(5L, "try-abc-123").getOrderId()).isEqualTo(first);
		assertThat(orderData.findByCreateKey(6L, "try-abc-123").getOrderId()).isEqualTo(other);
		assertThat(orderData.findByCreateKey(7L, "try-abc-123")).isNull();
	}

	@Test
	@DisplayName("🔴 결제 시도(V27) — 실제 PostgreSQL 에서 한 창만 결제를 시작하고, 결제가 붙으면 아무 창도 시작하지 못한다")
	void paymentAttemptIsOnePerOrder() throws Exception {
		Object target = org.springframework.test.util.AopTestUtils.getTargetObject(orderData);
		assertThat(org.springframework.test.util.ReflectionTestUtils.getField(target, "entityManager"))
				.as("겹친 결제의 늦은 쪽이 다시 읽으려면 EntityManager 가 주입돼야 한다(없으면 예전처럼 경보 없이 거절)").isNotNull();
		Long id = newOrderWithAddress();
		String trackingId = jdbcString("select tracking_id from orders.orders where order_id = " + id);

		orderData.startPaymentAttempt(trackingId, "tab-one-attempt", java.time.Duration.ofMinutes(10));
		assertThat(jdbcString("select payment_attempt from orders.orders where order_id = " + id)).isEqualTo("tab-one-attempt");
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> orderData.startPaymentAttempt(trackingId, "tab-two-attempt", java.time.Duration.ofMinutes(10)))
				.hasMessage(com.project.order_service.service.OrderDataService.OTHER_WINDOW_MESSAGE);
		orderData.startPaymentAttempt(trackingId, "tab-one-attempt", java.time.Duration.ofMinutes(10));

		jdbc("update orders.orders set payment_provider = 'PAYPAL', provider_payment_id = 'cap-" + id + "' where order_id = " + id);
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> orderData.startPaymentAttempt(trackingId, "tab-one-attempt", java.time.Duration.ofMinutes(10)))
				.hasMessage(com.project.order_service.service.OrderDataService.ALREADY_PAID_MESSAGE);
	}

	private String jdbcString(String sql) throws Exception {
		try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	/** 품목(색상 7) 하나를 가진 주문. */
	private Long newOrderWithProduct() {
		return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(st -> {
			var address = com.project.order_service.model.OrderAddress.builder()
					.firstname("길동").lastname("홍").address1("세종대로 1").city("서울").state("서울")
					.zipCode("04524").country("KR").phoneNumber("010-0000-0000").build();
			addresses.save(address);
			var order = com.project.order_service.model.Order.builder()
					.customerId(5L).trackingId("t-" + java.util.UUID.randomUUID())
					.orderStatus(com.project.common.constants.OrderStatus.NOT_PROCESSED)
					.shippingAddress(address).build();
			var product = com.project.order_service.model.OrderProduct.builder()
					.name("티셔츠").size("M").qty(1).colorId(7L).productId(10L).price(new java.math.BigDecimal("10.00"))
					.order(order).build();
			order.setOrderedProducts(new java.util.ArrayList<>(java.util.List.of(product)));
			return orders.save(order).getOrderId();
		});
	}

	@Test
	@DisplayName("🔴 B-87: 주문 상세 · 목록의 색상(catalog) 조회는 트랜잭션 밖이다 — 그동안 DB 커넥션을 쥐지 않는다")
	void orderViewCallsCatalogOutsideATransaction() throws Exception {
		Long id = newOrderWithProduct();
		java.util.List<Boolean> inTx = new java.util.ArrayList<>();
		org.mockito.Mockito.when(catalog.getColorsInfo(org.mockito.ArgumentMatchers.anyList())).thenAnswer(i -> {
			inTx.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
			return org.springframework.http.ResponseEntity.ok(java.util.List.of(
					com.project.common.dto.ProductColorDto.builder().id("7").color("red").build()));
		});

		var detail = orderService.getOrder(id, 5L, "");
		var page = orderService.getOrdersPage(5L, "", null, 50);

		assertThat(detail.getProducts().get(0).getColor().getColor()).isEqualTo("red");
		assertThat(detail.getShippingAddress().getCity()).isEqualTo("서울");
		assertThat(page.getContent()).isNotEmpty();
		assertThat(inTx).isNotEmpty().as("catalog 를 부르는 동안 트랜잭션이 없어야 한다").containsOnly(false);
	}

	@Test
	@DisplayName("🔴 B-87: 추적 번호로 읽은 주문은 트랜잭션 밖에서도 품목 · 배송지를 쓸 수 있다(결제 시작 · 입금 안내 · Stripe)")
	void trackingIdLookupLoadsProductsAndAddress() throws Exception {
		Long id = newOrderWithProduct();
		String trackingId = jdbcString("select tracking_id from orders.orders where order_id = " + id);

		var order = orderData.getOrderByTrackingId(trackingId);

		assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(order.getOrderedProducts()).hasSize(1);
		assertThat(order.getOrderedProducts().get(0).getName()).isEqualTo("티셔츠");
		assertThat(order.getShippingAddress().getCity()).isEqualTo("서울");
	}
}

