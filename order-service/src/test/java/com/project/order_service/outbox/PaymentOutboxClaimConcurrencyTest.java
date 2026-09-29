package com.project.order_service.outbox;

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
 * 아웃박스 선점이 레플리카를 늘려도 안전한지 — 실제 동시 트랜잭션으로 확인한다.
 *
 * <p>이 테스트가 지키는 것: 레플리카를 2개로 올리는 순간 두 파드의 스케줄러가 동시에 폴링한다.
 * 예전처럼 잠금 없이 조회하면 <b>양쪽이 같은 행을 읽어 같은 결제 요청을 두 번 발행</b>한다.
 * 단위 테스트로는 잡히지 않는다 — 진짜 동시 트랜잭션 두 개와 진짜 PostgreSQL 이 있어야 한다.
 *
 * <p>구조: 두 스레드가 각자의 트랜잭션에서 선점 쿼리를 돌리고, <b>둘 다 끝날 때까지 커밋하지
 * 않는다.</b> 커밋을 미루는 것이 핵심이다 — 먼저 커밋해 버리면 잠금이 풀려 두 번째 스레드가
 * 같은 행을 정상적으로 가져가고, 그러면 SKIP LOCKED 가 동작하는지 알 수 없다.
 */
@Tag("containers")
@Testcontainers
@SpringBootTest
@DisplayName("아웃박스 선점 동시성 (레플리카 2개 안전성)")
class PaymentOutboxClaimConcurrencyTest {

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
    private PaymentOutboxRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final int EVENTS = 10;
    private static final int BATCH = 4;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        for (int i = 0; i < EVENTS; i++) {
            repository.save(new PaymentOutboxEvent(
                    "order-" + i, "Order", "payment-request", "{\"orderId\":" + i + "}",
                    OutboxStatus.STARTED));
        }
    }

    @Test
    @DisplayName("동시에 폴링한 두 레플리카는 서로 겹치지 않는 이벤트를 가져간다")
    void concurrentClaimsAreDisjoint() throws Exception {
        List<Set<String>> claims = Collections.synchronizedList(new ArrayList<>());

        // 두 스레드 모두 선점을 마칠 때까지 아무도 커밋하지 않게 잡아 둔다.
        CountDownLatch bothClaimed = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int replica = 0; replica < 2; replica++) {
                pool.submit(() -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        Set<String> ids = new HashSet<>();
                        repository.claimByOutboxStatus(OutboxStatus.STARTED, PageRequest.of(0, BATCH))
                                .forEach(e -> ids.add(e.getId()));
                        claims.add(ids);
                        bothClaimed.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                });
            }

            assertThat(bothClaimed.await(60, TimeUnit.SECONDS))
                    .as("두 트랜잭션이 모두 선점을 마쳐야 한다 — 시간이 초과됐다면 SKIP LOCKED 가 아니라 "
                            + "일반 FOR UPDATE 로 서로를 기다리고 있다는 뜻이다")
                    .isTrue();
        } finally {
            release.countDown();
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }

        assertThat(claims).hasSize(2);
        Set<String> first = claims.get(0);
        Set<String> second = claims.get(1);

        Set<String> overlap = new HashSet<>(first);
        overlap.retainAll(second);

        assertThat(overlap)
                .as("두 레플리카가 같은 아웃박스 이벤트를 집으면 결제 요청이 두 번 발행된다. "
                        + "겹친 id: %s", overlap)
                .isEmpty();

        assertThat(first).as("각 레플리카는 배치 한도까지 실제로 일감을 가져가야 한다").hasSize(BATCH);
        assertThat(second).hasSize(BATCH);
    }

    @Test
    @DisplayName("선점은 배치 크기를 넘지 않는다 — 한 레플리카가 전부 잠그지 않는다")
    void claimRespectsBatchSize() {
        List<PaymentOutboxEvent> claimed = new TransactionTemplate(transactionManager).execute(status ->
                repository.claimByOutboxStatus(OutboxStatus.STARTED, PageRequest.of(0, BATCH)));

        assertThat(claimed).hasSize(BATCH);
        assertThat(repository.findByOutboxStatus(OutboxStatus.STARTED).orElseThrow())
                .as("나머지는 여전히 대기 상태로 남아 다음 폴링이나 다른 레플리카가 가져간다")
                .hasSize(EVENTS);
    }
}
