package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 필터 메뉴 계산은 클러스터에서 한 파드만 — 파드 둘(이 빈 둘)이 캐시(Redis 대역)와 맡기(Redis 대역)를 함께 쓴다.
 *
 * <p>🐛 2026-10-21 실습 8시간(상품 1,000만, docs/83 §2.8): 메뉴가 오래된 순간을 모든 파드가 같이 보아 같은 DISTINCT(브랜드 22초)를 파드 셋이
 * 동시에 돌렸다 — DB 가 느려져 home 까지 6초, 게이트웨이 503 1,472건.
 *
 * <p>[JA] フィルターメニューの計算はクラスタで一つのポッドだけ — ポッド二つがキャッシュと担当(Redis の代わり)を共有する。
 */
@DisplayName("필터 메뉴 — 파드 사이에서 한 번만 계산(Redis 맡기)")
class SearchFilterFacetsClaimTest {

    private static final long NOW = 1_800_000_000_000L;

    /** Redis 의 SET NX 와 같은 뜻 — 파드들이 함께 쓴다. */
    static final class SharedClaim implements FacetRefreshClaim {
        final Map<String, String> held = new ConcurrentHashMap<>();

        @Override
        public String tryClaim(String flightKey) {
            String mine = UUID.randomUUID().toString();
            return held.putIfAbsent(flightKey, mine) == null ? mine : null;
        }

        @Override
        public void release(String flightKey, String token) {
            held.remove(flightKey, token);
        }
    }

    private final ExecutorService podAThread = Executors.newSingleThreadExecutor();
    private final ConcurrentMapCacheManager redisCache = new ConcurrentMapCacheManager();
    private final SharedClaim claim = new SharedClaim();

    @AfterEach
    void stop() {
        podAThread.shutdownNow();
    }

    private static Clock clock() {
        return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    }

    private SearchFilterFacets pod(java.util.concurrent.Executor executor, FacetRefreshClaim claim) {
        return new SearchFilterFacets(mock(FacetQueries.class), redisCache, executor, Duration.ofMinutes(10), Duration.ofMillis(500),
                clock(), claim);
    }

    /** 계산 횟수를 세고, 풀어 줄 때까지 멈춰 있는 질의(브랜드 DISTINCT 22초 대역). */
    private static Supplier<List<String>> slowQuery(AtomicInteger runs, CountDownLatch started, CountDownLatch release) {
        return () -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of("new-a", "new-b");
        };
    }

    @Test
    @DisplayName("🔴 오래된 메뉴를 두 파드가 같이 보면 계산은 한 파드에서 한 번 — 다른 파드는 옛 값을 내주고 질의하지 않는다(예전: 파드마다)")
    void staleMenuIsRefreshedByOnePodOnly() throws Exception {
        redisCache.getCache("brands_cache").put("id:3", new FacetEntry(new java.util.ArrayList<>(List.of("old")), NOW - Duration.ofMinutes(11).toMillis()));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SearchFilterFacets podA = pod(podAThread, claim);
        SearchFilterFacets podB = pod(Runnable::run, claim);

        assertThat(podA.lookup("brands_cache", "id:3", slowQuery(runs, started, release)).get()).containsExactly("old");
        assertThat(started.await(5, TimeUnit.SECONDS)).as("파드 A 가 맡아 계산 중").isTrue();

        CompletableFuture<List<Object>> b = podB.lookup("brands_cache", "id:3", slowQuery(runs, new CountDownLatch(1), release));
        assertThat(b.get()).as("파드 B 는 옛 값을 그대로").containsExactly("old");
        assertThat(runs.get()).as("파드 B 는 질의하지 않았다").isEqualTo(1);

        release.countDown();
        for (int i = 0; i < 100 && !claim.held.isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertThat(claim.held).as("끝나면 맡기를 푼다").isEmpty();
        assertThat(((FacetEntry) redisCache.getCache("brands_cache").get("id:3").get()).getValues()).containsExactly("new-a", "new-b");
        assertThat(podB.lookup("brands_cache", "id:3", slowQuery(runs, new CountDownLatch(1), release)).get())
                .as("파드 B 의 다음 요청은 새 값 — 계산 없이").containsExactly("new-a", "new-b");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 아직 없는 메뉴도 한 번 — 다른 파드는 기다리지 않고 메뉴 없이(빈 목록), 계산이 끝나면 캐시에서")
    void missingMenuIsComputedByOnePodOnly() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SearchFilterFacets podA = pod(podAThread, claim);
        SearchFilterFacets podB = pod(Runnable::run, claim);

        CompletableFuture<List<Object>> a = podA.lookup("sizes_cache", "3", slowQuery(runs, started, release));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<Object>> b = podB.lookup("sizes_cache", "3", slowQuery(runs, new CountDownLatch(1), release));
        assertThat(b).as("다른 파드가 계산 중 — 이 파드는 계산하지 않는다").isCompletedExceptionally();
        try {
            b.get();
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(SearchFilterFacets.ClaimedElsewhere.class);
        }
        release.countDown();
        assertThat(a.get(5, TimeUnit.SECONDS)).containsExactly("new-a", "new-b");
        assertThat(podB.lookup("sizes_cache", "3", slowQuery(runs, new CountDownLatch(1), release)).get()).containsExactly("new-a", "new-b");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("맡은 직후 캐시가 이미 새것이면(다른 파드가 방금 썼다) 다시 계산하지 않는다")
    void freshEntryAfterClaimSkipsTheQuery() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        FacetRefreshClaim writesThenGrants = new FacetRefreshClaim() {
            @Override
            public String tryClaim(String flightKey) {
                redisCache.getCache("colors_cache").put("3", new FacetEntry(new java.util.ArrayList<>(List.of("Red")), NOW));   // 다른 파드가 막 끝냈다
                return "t";
            }

            @Override
            public void release(String flightKey, String token) {
            }
        };
        SearchFilterFacets pod = pod(Runnable::run, writesThenGrants);
        assertThat(pod.lookup("colors_cache", "3", () -> {
            runs.incrementAndGet();
            return List.of("Navy");
        }).get()).containsExactly("Red");
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("Redis 에 닿지 못하면(맡기 예외) 예전처럼 이 파드에서 계산한다 — 메뉴가 없는 것보다 낫다")
    void claimFailureFallsBackToLocalCompute() throws Exception {
        FacetRefreshClaim down = new FacetRefreshClaim() {
            @Override
            public String tryClaim(String flightKey) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void release(String flightKey, String token) {
                throw new AssertionError("맡지 않았으니 풀 것도 없다");
            }
        };
        assertThat(pod(Runnable::run, down).lookup("details_cache", "3", () -> List.of("x")).get()).containsExactly("x");
    }

    @Test
    @DisplayName("Redis 맡기 — 키는 접두사 + SHA-256(요청의 카테고리 이름 길이와 상관없이) · 만료 = 메뉴 질의 상한 + 1분 · 풀 때는 자기 토큰만")
    @SuppressWarnings("unchecked")
    void redisClaimUsesSetNxWithLeaseAndTokenRelease() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        String key = FacetRefreshClaim.Redis.redisKey("brands_cache::name:" + "x".repeat(5000));
        when(ops.setIfAbsent(eq(key), anyString(), eq(Duration.ofSeconds(660)))).thenReturn(true, false);

        FacetRefreshClaim.Redis claim = new FacetRefreshClaim.Redis(redis, 600);
        assertThat(key).startsWith(FacetRefreshClaim.Redis.PREFIX).hasSize(FacetRefreshClaim.Redis.PREFIX.length() + 64);
        assertThat(claim.lease()).isEqualTo(Duration.ofSeconds(660));
        String token = claim.tryClaim("brands_cache::name:" + "x".repeat(5000));
        assertThat(token).isNotNull();
        assertThat(claim.tryClaim("brands_cache::name:" + "x".repeat(5000))).as("다른 파드가 쥐고 있다").isNull();
        claim.release("brands_cache::name:" + "x".repeat(5000), token);
        verify(redis).execute(any(), eq(List.of(key)), eq(token));
    }
}
