package com.project.userservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 2FA 대기 토큰의 시도 제한 — 실제 Redis.
 *
 * <p>🐛 예전: 읽고(userIdOf) → 검증하고 → 틀리면 더했다(recordFailure) — 동시에 보낸 코드가 모두 "아직 0번" 을 읽고 전부 검증됐다.
 * 증가에도 틈이 있었다: {@code INCR} 다음 줄의 {@code expire} 사이에 끊기면 TTL 없는 키가 남아 그 사용자의 2FA 가 영영 막혔다.
 *
 * <p>[JA] 2FA 待機トークンの試行制限 — 実 Redis。以前は読む → 検証 → 加算で、同時に送ったコードがすべて検証された。
 */
@Tag("containers")
@Testcontainers
@DisplayName("2FA 대기 토큰 시도 제한 (실제 Redis)")
class PendingTwoFactorTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private PendingTwoFactor pending;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword("testpw");
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        pending = new PendingTwoFactor("test-jwt-secret-test-jwt-secret-32", template);
    }

    @Test
    @DisplayName("🔴 같은 토큰으로 동시에 50번 보내도 검증까지 가는 것은 다섯 번뿐")
    void concurrentAttemptsAreCapped() throws Exception {
        String token = pending.issue(7L);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return pending.reserveAttempt(token).isPresent();
            }));
        }
        start.countDown();
        int passed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                passed++;
            }
        }
        pool.shutdown();
        // 🐛 읽고-검증하고-더하는 순서면 50개가 모두 "아직 0번" 을 읽고 통과했다.
        assertThat(passed).isEqualTo(5);
    }

    @Test
    @DisplayName("다섯 번째는 '더 없음' — 마지막인지 알려 안내 문구가 갈린다")
    void theFifthAttemptIsTheLast() {
        String token = pending.issue(7L);
        for (int i = 0; i < 4; i++) {
            assertThat(pending.reserveAttempt(token)).get()
                    .extracting(PendingTwoFactor.Attempt::moreAfterThis).isEqualTo(true);
        }
        assertThat(pending.reserveAttempt(token)).get()
                .extracting(PendingTwoFactor.Attempt::userId, PendingTwoFactor.Attempt::moreAfterThis)
                .containsExactly(7L, false);
        assertThat(pending.reserveAttempt(token)).isEmpty();
    }


    @Test
    @DisplayName("🔴 센 키에는 반드시 만료가 붙는다 — TTL 없는 키는 그 사용자의 2FA 를 영영 막는다")
    void theCounterAlwaysExpires() {
        String token = pending.issue(7L);
        pending.reserveAttempt(token);
        List<String> keys = new ArrayList<>(template.keys("2fa:pending:*"));
        assertThat(keys).hasSize(1);
        assertThat(template.getExpire(keys.get(0))).isPositive();
    }


    @Test
    @DisplayName("토큰마다 따로 센다 — 다 쓴 토큰이 다른 사람의 토큰을 막지 않는다")
    void tokensAreCountedSeparately() {
        String mine = pending.issue(7L);
        String yours = pending.issue(8L);
        for (int i = 0; i < 5; i++) {
            pending.reserveAttempt(mine);
        }
        assertThat(pending.reserveAttempt(mine)).isEmpty();

        Optional<PendingTwoFactor.Attempt> first = pending.reserveAttempt(yours);
        assertThat(first).get().extracting(PendingTwoFactor.Attempt::userId).isEqualTo(8L);
    }
}
