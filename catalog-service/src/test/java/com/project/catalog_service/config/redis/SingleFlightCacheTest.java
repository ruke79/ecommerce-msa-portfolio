package com.project.catalog_service.config.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.domain.Pageable;

import com.project.catalog_service.service.SearchEngine;
import com.project.catalog_service.service.SearchService;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 키마다 한 번 계산(SingleFlightCache) — 같은 키의 동시 미스는 한 스레드만 계산하고, 다른 키는 서로 기다리지 않는다.
 *
 * <p>🐛 2026-10-19 mixed {@code 160904Z}(docs/83 §5): 같은 초에 만료된 검색 키 48개마다 들어온 요청이 모두 DB 로 갔다 —
 * {@code sync = true} 는 Spring Data Redis 3.x 의 잠그지 않는 작성기에서 아무것도 하지 않았다(실제 Redis 로는 {@code CacheStampedeRedisTest}).
 *
 * <p>[JA] キーごとに一度だけ計算 — 同じキーの同時ミスは一つのスレッドだけが計算し、別のキーは互いに待たない。
 */
@DisplayName("키마다 한 번 계산 — SingleFlightCache")
class SingleFlightCacheTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    /** 모든 스레드가 함께 출발해 같은 캐시의 get(key, loader) 를 부른다. */
    private List<Object> race(Cache cache, int threads, java.util.function.IntFunction<Object> key, java.util.concurrent.Callable<Object> loader)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            calls.add(pool.submit(() -> {
                start.await();
                return cache.get(key.apply(n), loader);
            }));
        }
        start.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : calls) {
            out.add(f.get(10, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("🔴 같은 키의 동시 미스 16개 — 계산은 한 번, 나머지는 기다렸다가 그 값을 받는다(예전: 16번 모두 DB)")
    void sameKeyLoadsOnce() throws Exception {
        SingleFlightCache cache = new SingleFlightCache(new ConcurrentMapCache("search_cache"));
        AtomicInteger loads = new AtomicInteger();

        List<Object> got = race(cache, 16, n -> "category=3&page=1", () -> {
            loads.incrementAndGet();
            Thread.sleep(200);
            return "result";
        });

        assertThat(loads).hasValue(1);
        assertThat(got).containsOnly("result").hasSize(16);
        assertThat(cache.inFlight()).as("끝나면 잠금이 남지 않는다").isZero();
    }

    @Test
    @DisplayName("🔴 다른 키는 서로 기다리지 않는다 — 키 8개 · 계산 300ms 가 함께 끝난다(캐시 하나의 잠금이면 2.4초)")
    void differentKeysDoNotWait() throws Exception {
        SingleFlightCache cache = new SingleFlightCache(new ConcurrentMapCache("search_cache"));
        AtomicInteger loads = new AtomicInteger();

        long t0 = System.nanoTime();
        race(cache, 8, n -> "page=" + n, () -> {
            loads.incrementAndGet();
            Thread.sleep(300);
            return "result";
        });
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(loads).hasValue(8);
        assertThat(millis).as("8 × 300ms 를 줄 세우지 않는다").isLessThan(1500);
    }

    @Test
    @DisplayName("null 은 담지 않고 다음 읽기가 다시 계산한다 · 계산 실패는 ValueRetrievalException 이고 잠금이 남지 않는다")
    void nullAndFailureAreNotCached() {
        SingleFlightCache cache = new SingleFlightCache(new ConcurrentMapCache("products", false));
        AtomicInteger loads = new AtomicInteger();

        Object none = cache.get("k", () -> { loads.incrementAndGet(); return null; });
        assertThat(none).isNull();
        assertThat(cache.get("k")).isNull();
        Object again = cache.get("k", () -> { loads.incrementAndGet(); return "v"; });
        assertThat(again).isEqualTo("v");
        assertThat(loads).hasValue(2);

        assertThatThrownBy(() -> cache.get("boom", () -> { throw new IllegalStateException("db"); }))
                .isInstanceOf(Cache.ValueRetrievalException.class)
                .hasRootCauseMessage("db");
        assertThat(cache.get("boom")).isNull();
        assertThat(cache.inFlight()).isZero();
    }

    @Test
    @DisplayName("이미 있는 값은 계산 없이 · 쓰기 · 비우기는 안쪽 캐시 그대로")
    void passesThrough() {
        ConcurrentMapCache inner = new ConcurrentMapCache("products");
        SingleFlightCache cache = new SingleFlightCache(inner);
        cache.put("a", "1");
        Object hit = cache.get("a", () -> { throw new AssertionError("계산하면 안 된다"); });
        assertThat(hit).isEqualTo("1");
        cache.evict("a");
        assertThat(inner.get("a")).isNull();
        cache.put("b", "2");
        cache.clear();
        assertThat(inner.get("b")).isNull();
        assertThat(cache.getName()).isEqualTo("products");
        assertThat(cache.getNativeCache()).isSameAs(inner.getNativeCache());
    }

    @Test
    @DisplayName("🔴 검색 캐시 둘은 sync = true — 빼면 만료 순간 같은 검색을 요청마다 다시 계산한다")
    void searchCachesAskForSingleFlight() throws Exception {
        Method search = SearchService.class.getMethod("searchProducts", SearchParamsRequest.class, SearchEngine.class);
        Method byCategory = SearchService.class.getMethod("findAllByCategory", Long.class, Pageable.class);
        assertThat(search.getAnnotation(Cacheable.class).sync()).as("search_cache").isTrue();
        assertThat(byCategory.getAnnotation(Cacheable.class).sync()).as("products_by_category").isTrue();
    }
}
