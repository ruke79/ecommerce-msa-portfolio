package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;

/**
 * 배송 상태 원장(V30) — 실제 PostgreSQL · 스프링 트랜잭션에서. ProcessedEventLedger 의 원장 쓰기가 apply 의 트랜잭션과 함께 커밋되고, 같은 키의
 * 두 번째 apply 는 아무것도 하지 않는다(주문이 없는 번호로 — 상태 전이와 무관하게 원장만 본다).
 *
 * <p>[JA] 配送状態の台帳(V30)— 本物の PostgreSQL とトランザクションで、台帳の書き込みが apply と一緒にコミットされ、同じキーの二度目は何もしない。
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("배송 상태 원장 (V30, PostgreSQL · 트랜잭션)")
class DeliveryStatusLedgerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("orderdb")
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
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpw");
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("eureka.client.enabled", () -> false);
        registry.add("eureka.client.register-with-eureka", () -> false);
        registry.add("eureka.client.fetch-registry", () -> false);
        registry.add("management.tracing.enabled", () -> false);
        registry.add("server.ssl.enabled", () -> false);
        // PayPal 검증은 자격증명이 없으면 **기동을 거부한다**(PaypalConfig — 의도된 fail closed).
        // 테스트에는 시크릿이 없으므로 명시적으로 끈다. 이 한 줄이 필요하다는 사실 자체가
        // 기본값이 안전한 쪽으로 잡혀 있다는 증거다(docs/35).
        registry.add("paypal.verification.mode", () -> "disabled");
        // 스케줄러가 배경에서 돌며 행을 가져가면 이 테스트가 불안정해진다.
        registry.add("order-service.outbox-scheduler-fixed-rate", () -> 3_600_000);
        registry.add("order-service.outbox-scheduler-initial-delay", () -> 3_600_000);
        registry.add("order-service.outbox-relay-on-commit", () -> false);
    }

    @Autowired
    private OrderDeliveryStatusService service;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("🔴 원장은 apply 와 함께 커밋되고 같은 키는 한 번만 — 다른 배송 건은 따로")
    void ledgerCommitsWithApplyAndTakesEachKeyOnce() {
        String key = OrderDeliveryStatusService.ledgerKey("9001", "CANCELLED");
        service.apply(987654321L, "CANCELLED", "CANCELLED", key);
        assertThat(jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?", Integer.class, key)).isEqualTo(1);

        assertThat(service.apply(987654321L, "CANCELLED", "CANCELLED", key)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?", Integer.class, key)).isEqualTo(1);

        service.apply(987654321L, "CANCELLED", "CANCELLED", OrderDeliveryStatusService.ledgerKey("9002", "CANCELLED"));
        assertThat(jdbc.queryForObject("select count(*) from orders.processed_event where event_id like 'delivery-status:900%'",
                Integer.class)).isEqualTo(2);
    }
}
