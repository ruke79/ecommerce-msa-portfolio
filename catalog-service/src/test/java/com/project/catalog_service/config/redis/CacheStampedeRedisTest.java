package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 만료 순간의 몰림(실제 Redis, 운영과 같은 캐시 매니저) — 같은 키의 동시 미스는 한 번만 계산하고, 수명은 키마다 흩어진다.
 *
 * <p>🐛 2026-10-19 mixed {@code 160904Z}(docs/83 §5): 한꺼번에 채워진 {@code search_cache} 키 48개가 같은 초에 만료됐고(Redis 의
 * 만료 시각이 48개 모두 17:15), {@code sync = true} 인데도 키마다 들어온 요청이 모두 DB 로 가 20초 동안 커넥션 대기 22 · 서버 p95 1.5초.
 *
 * <p>[JA] 期限切れの瞬間の殺到(本物の Redis、本番と同じキャッシュマネージャ)— 同じキーの同時ミスは一度だけ計算し、寿命はキーごとに散る。
 */
@Tag("containers")
@Testcontainers
@DisplayName("만료 순간의 몰림 — 실제 Redis")
class CacheStampedeRedisTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "testpw");

    static LettuceConnectionFactory factory;
    static RedisCacheManager manager;
    static ExecutorService pool;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword(RedisPassword.of("testpw"));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        manager = new RedisCacheConfig(null).redisCacheManager(factory);
        manager.afterPropertiesSet();
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterAll
    static void close() {
        pool.shutdownNow();
        factory.destroy();
    }

    private static int racedLoads(Cache cache, String key) throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            Thread.sleep(200);   // 100만 상품에서 캐시 밖 검색 한 번(수십 ~ 수백 ms)
            return "result";
        };
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return cache.get(key, loader);
            }));
        }
        start.countDown();
        for (Future<String> f : calls) {
            assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("result");
        }
        return loads.get();
    }

    @Test
    @DisplayName("🔴 search_cache 의 같은 키 동시 미스 16개 — 계산은 한 번(예전 매니저: 16번)")
    void managerLoadsEachKeyOnce() throws Exception {
        assertThat(racedLoads(manager.getCache("search_cache"), "category=3&page=1")).isEqualTo(1);
    }

    @Test
    @DisplayName("근거 — 라이브러리 그대로의 RedisCache 는 sync 읽기에도 잠그지 않는다(이게 깨지면 Spring Data Redis 가 잠그게 바뀐 것 — SingleFlightCache 를 다시 본다)")
    void plainRedisCacheDoesNotLock() throws Exception {
        Cache plain = ((SingleFlightCache) manager.getCache("search_cache")).delegate();
        assertThat(racedLoads(plain, "category=4&page=1")).isGreaterThan(1);
    }

    @Test
    @DisplayName("🔴 한꺼번에 넣은 키 200개의 Redis 만료가 흩어진다 — 60분에서 최대 12분 짧게(예전: 모두 같은 초)")
    void expiriesAreSpreadInRedis() {
        Cache cache = manager.getCache("search_cache");
        for (int i = 0; i < 200; i++) {
            cache.put("spread-" + i, "v");
        }
        long min = Long.MAX_VALUE, max = 0;
        try (var c = factory.getConnection()) {
            for (int i = 0; i < 200; i++) {
                Long ttl = c.keyCommands().pTtl(("search_cache::spread-" + i).getBytes());
                assertThat(ttl).isPositive();
                min = Math.min(min, ttl);
                max = Math.max(max, ttl);
            }
        }
        assertThat(max).isLessThanOrEqualTo(TimeUnit.MINUTES.toMillis(60));
        assertThat(min).isGreaterThanOrEqualTo(TimeUnit.MINUTES.toMillis(48) - 5_000);
        assertThat(max - min).as("같은 초에 몰리지 않는다").isGreaterThan(TimeUnit.MINUTES.toMillis(6));
    }
}
