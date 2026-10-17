package com.project.userservice.security.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
 * 🔴 OTP 한 번만 쓰기 · 2FA 변경 시도 제한 — <b>실제 Redis</b>(Lua 의 원자성은 모형으로 확인할 수 없다, docs/68 §14).
 */
@Tag("containers")
@Testcontainers
@DisplayName("TOTP 재사용 거절 (실제 Redis)")
class TotpReplayGuardTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "testpw");

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private TotpReplayGuard guard;

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
        guard = new TotpReplayGuard(template, 3, 60_000);
    }

    @Test
    @DisplayName("🔴 같은 구간의 코드는 두 번 받지 않는다 — 더 이른 구간도")
    void sameOrEarlierStepIsReplay() {
        assertThat(guard.claim(7L, 1000)).isTrue();
        assertThat(guard.claim(7L, 1000)).isFalse();
        assertThat(guard.claim(7L, 999)).isFalse();
        assertThat(guard.claim(7L, 1001)).isTrue();
    }

    @Test
    @DisplayName("다른 사용자의 기록과 섞이지 않는다")
    void perUser() {
        assertThat(guard.claim(7L, 1000)).isTrue();
        assertThat(guard.claim(8L, 1000)).isTrue();
    }

    @Test
    @DisplayName("🔴 같은 코드로 8개가 동시에 와도 하나만 통과한다")
    void concurrentClaims() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return guard.claim(7L, 2000);
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : fs) {
            ok += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
    }

    @Test
    @DisplayName("변경 시도를 세어 막고, 성공하면 지운다 · 센 키에는 만료가 붙는다")
    void failureLimit() {
        // 호출부(UserServiceImpl.checkCode)와 같은 판정 — 돌려준 수가 한도를 넘으면 검증하지 않는다.
        for (int i = 1; i <= guard.maxFailures(); i++) {
            assertThat(guard.reserveAttempt(7L)).isEqualTo(i).isLessThanOrEqualTo(guard.maxFailures());
        }
        assertThat(guard.reserveAttempt(7L)).isGreaterThan(guard.maxFailures());
        // 창이 지나면 저절로 풀린다 — 영구 잠금은 남의 계정을 잠그는 DoS 버튼이 된다.
        assertThat(template.getExpire(TotpReplayGuard.FAIL_KEY + 7L)).isPositive();

        guard.clearFailures(7L);
        assertThat(guard.reserveAttempt(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 사용자마다 따로 센다 — 한 사람이 막혀도 다른 사람은 그대로")
    void attemptsAreCountedPerUser() {
        for (int i = 0; i <= guard.maxFailures(); i++) {
            guard.reserveAttempt(7L);
        }
        assertThat(guard.reserveAttempt(7L)).isGreaterThan(guard.maxFailures());
        assertThat(guard.reserveAttempt(8L)).isEqualTo(1);
    }
}
