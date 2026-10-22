package com.project.catalog_service.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDetailsDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 검색 첫 쪽의 필터 메뉴(브랜드 · 색상 · 사이즈 · 상세) — 카테고리마다 중복 없는 값. <b>요청은 계산하지 않는다</b>: 캐시를 읽고, 없거나
 * 오래됐으면 전용 실행기에 계산을 맡긴 뒤 짧게만 기다린다({@code catalog.facets.wait}, 기본 1.5초). 그 안에 못 끝나면 메뉴 없이 결과만 나간다.
 *
 * <p>🐛 2026-10-20 실습(상품 1,000만, docs/83 §2.8): 메뉴 질의가 요청 안에서 돌았다(사이즈 DISTINCT 76초 — 큰 카테고리의 SKU × 사이즈
 * 전부를 조인한다). 캐시(8 ~ 10분)가 만료될 때마다 다시 돌며 커넥션을 몇 분씩 쥐었고, 파드마다 5개뿐인 풀이 차서 home · banners 까지
 * 5초 대기 끝에 5xx 였다(넓힘 측정 실패 10.33%, 두 번째 붕괴가 첫 채움 9분 뒤). 메뉴는 검색 결과 캐시({@code search_cache}, 60분)에도
 * 함께 들어 있어 오래 그대로였다 — 이제 결과 캐시에는 넣지 않고 매 요청 이 빈이 붙인다({@link #attach}).
 *
 * <ul>
 *   <li>값과 계산 시각을 함께 둔다({@link FacetEntry}, 캐시 24시간). {@code catalog.facets.refresh-after}(기본 10분)가 지나면 옛 값을
 *       내주면서 뒤에서 다시 계산한다 — 만료 순간 요청이 질의를 기다리지 않는다.</li>
 *   <li>계산은 키마다 하나(파드 안), 실행기 스레드는 {@code catalog.facets.threads}(기본 1)개 — 메뉴 질의가 파드의 커넥션을 한 개 넘게
 *       쓰지 않는다. 질의 자체의 시간 상한은 {@link FacetQueries}(기본 600초).</li>
 *   <li>그리고 <b>클러스터에서 한 파드만</b>({@link FacetRefreshClaim}) — 맡지 못한 파드는 옛 값을 그대로(없으면 메뉴 없이) 내주고, 새 값은 맡은
 *       파드가 쓴다. 🐛 2026-10-21 실습: 오래된 순간을 모든 파드가 같이 보아 같은 DISTINCT 를 파드 셋이 동시에 돌렸다 — DB 가 느려져
 *       home 까지 6초, 게이트웨이 503 1,472건(docs/83 §2.8). Redis 에 닿지 못하면 예전처럼 이 파드에서 계산한다.</li>
 *   <li>상품 쓰기의 {@code @CacheEvict}(ProductService)가 지우면 다음 요청이 다시 계산을 맡긴다.</li>
 * </ul>
 *
 * <p>[JA] 検索 1 ページ目のフィルターメニュー。リクエストは計算しない — キャッシュを読み、なければ専用実行器に任せて短く待つだけ。1,000 万商品で
 * メニュー照会(76 秒)がリクエスト内で走り、キャッシュ満了のたびに接続を数分握ってプールが詰まり、home まで 5xx だった。
 */
@Slf4j
@Service
public class SearchFilterFacets {

    private final FacetQueries queries;
    private final CacheManager cacheManager;
    private final Executor executor;
    private final Duration refreshAfter;
    private final Duration waitBudget;
    private final Clock clock;
    private final FacetRefreshClaim claim;
    private final Map<String, CompletableFuture<List<Object>>> inFlight = new ConcurrentHashMap<>();

    /** 다른 파드가 이 메뉴를 계산하고 있다 — 이 파드는 계산하지 않는다(기다리던 요청은 메뉴 없이). */
    static final class ClaimedElsewhere extends RuntimeException {
        ClaimedElsewhere() {
            super(null, null, false, false);
        }
    }

    @Autowired
    public SearchFilterFacets(FacetQueries queries, @Qualifier("redisCacheManager") CacheManager cacheManager,
            @Value("${catalog.facets.refresh-after:10m}") Duration refreshAfter,
            @Value("${catalog.facets.wait:1500ms}") Duration wait,
            @Value("${catalog.facets.threads:1}") int threads,
            FacetRefreshClaim claim) {
        this(queries, cacheManager, newExecutor(threads), refreshAfter, wait, Clock.systemUTC(), claim);
    }

    SearchFilterFacets(FacetQueries queries, CacheManager cacheManager, Executor executor, Duration refreshAfter, Duration wait,
            Clock clock) {
        this(queries, cacheManager, executor, refreshAfter, wait, clock, FacetRefreshClaim.LOCAL);
    }

    SearchFilterFacets(FacetQueries queries, CacheManager cacheManager, Executor executor, Duration refreshAfter, Duration wait,
            Clock clock, FacetRefreshClaim claim) {
        this.claim = claim;
        this.queries = queries;
        this.cacheManager = cacheManager;
        this.executor = executor;
        this.refreshAfter = refreshAfter;
        this.waitBudget = wait;
        this.clock = clock;
    }

    /** 데몬 스레드 · 대기열 상한 — 넘치면 그 계산은 다음 요청이 다시 맡긴다(메뉴만 비고 결과는 나간다). */
    private static ExecutorService newExecutor(int threads) {
        int n = Math.max(1, threads);
        return new ThreadPoolExecutor(n, n, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "facet-refresh");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }

    /**
     * 첫 쪽 결과에 메뉴를 붙인 복사본. {@code includeFilters=false} 면 그대로. 네 가지를 먼저 모두 맡기고 한 기한(기본 1.5초) 안에서 모은다 —
     * 하나씩 기다리면 최악이 네 배다.
     */
    public SearchResultDto attach(SearchResultDto dto, SearchParamsRequest params) {
        if (dto == null || params == null || !params.isIncludeFilters()) {
            return dto;
        }
        Long categoryId = SearchService.parseCategoryId(params.getCategory());
        String all = categoryId == null ? "all" : String.valueOf(categoryId);
        CompletableFuture<List<Object>> brands = categoryId != null
                ? lookup("brands_cache", "id:" + categoryId, () -> queries.brands(categoryId))
                : lookup("brands_cache", "name:" + params.getCategory(), () -> queries.brandsByCategoryName(params.getCategory()));
        CompletableFuture<List<Object>> colors = lookup("colors_cache", all, () -> queries.colors(categoryId));
        CompletableFuture<List<Object>> sizes = lookup("sizes_cache", all, () -> queries.sizes(categoryId));
        CompletableFuture<List<Object>> details = lookup("details_cache", all, () -> queries.details(categoryId));

        long deadline = System.nanoTime() + waitBudget.toNanos();
        return dto.toBuilder()
                .brandsDB(cast(await(brands, deadline)))
                .colors(cast(await(colors, deadline)))
                .sizes(cast(await(sizes, deadline)))
                .details(SearchFilterFacets.<ProductDetailsDto>cast(await(details, deadline)))
                .build();
    }

    /** 캐시에 있으면 그 값(오래됐으면 뒤에서 다시 계산을 맡긴다), 없으면 진행 중인 계산. */
    CompletableFuture<List<Object>> lookup(String cacheName, String key, Supplier<? extends List<?>> loader) {
        Cache cache = cacheManager.getCache(cacheName);
        FacetEntry hit = read(cache, cacheName, key);
        if (hit != null) {
            if (stale(hit)) {
                refresh(cache, cacheName, key, loader);
            }
            return CompletableFuture.completedFuture(hit.getValues());
        }
        return refresh(cache, cacheName, key, loader);
    }

    private FacetEntry read(Cache cache, String cacheName, String key) {
        if (cache == null) {
            return null;
        }
        try {
            FacetEntry hit = cache.get(key, FacetEntry.class);
            return hit != null && hit.getValues() != null ? hit : null;
        } catch (RuntimeException e) {
            // 옛 모양(목록 그대로 — 2026-10-20 전)이거나 Redis 오류 — 없는 것으로 보고 다시 만든다
            log.debug("필터 메뉴 캐시 읽기 실패 — 다시 계산 cache={} ({})", cacheName, e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean stale(FacetEntry entry) {
        return clock.millis() - entry.getComputedAtMillis() >= refreshAfter.toMillis();
    }

    /** 맡기를 확인하지 못해 이 파드에서 계산할 때의 표시 — 풀 것이 없다. */
    private static final String LOCAL_TOKEN = new String("unclaimed");

    private CompletableFuture<List<Object>> refresh(Cache cache, String cacheName, String key, Supplier<? extends List<?>> loader) {
        String flightKey = cacheName + "::" + key;
        CompletableFuture<List<Object>> mine = new CompletableFuture<>();
        CompletableFuture<List<Object>> running = inFlight.putIfAbsent(flightKey, mine);
        if (running != null) {
            return running;
        }
        try {
            executor.execute(() -> {
                String token = null;
                try {
                    try {
                        token = claim.tryClaim(flightKey);
                    } catch (RuntimeException e) {
                        token = LOCAL_TOKEN;   // 맡기를 확인하지 못했다(Redis) — 메뉴가 없는 것보다 낫다, 예전처럼 이 파드에서
                        log.debug("필터 메뉴 계산 맡기 실패 — 이 파드에서 계산 cache={} ({})", cacheName, e.getClass().getSimpleName());
                    }
                    if (token == null) {
                        mine.completeExceptionally(new ClaimedElsewhere());   // 옛 값은 캐시에 그대로 · 새 값은 맡은 파드가 쓴다
                        return;
                    }
                    // 맡기 직전에 다른 파드가 새로 써 두었으면 다시 하지 않는다
                    FacetEntry fresh = read(cache, cacheName, key);
                    if (fresh != null && !stale(fresh)) {
                        mine.complete(fresh.getValues());
                        return;
                    }
                    ArrayList<Object> values = new ArrayList<>(loader.get());
                    if (cache != null) {
                        try {
                            cache.put(key, new FacetEntry(values, clock.millis()));
                        } catch (RuntimeException e) {
                            log.warn("필터 메뉴 캐시 쓰기 실패 — 다음 요청이 다시 계산한다 cache={} ({})", cacheName, e.getClass().getSimpleName());
                        }
                    }
                    mine.complete(values);
                } catch (Throwable t) {
                    // 옛 값이 있으면 캐시에 그대로 남는다 — 지우지 않는다
                    log.warn("필터 메뉴 계산 실패 cache={} ({})", cacheName, t.getClass().getSimpleName());
                    mine.completeExceptionally(t);
                } finally {
                    if (token != null && token != LOCAL_TOKEN) {
                        claim.release(flightKey, token);
                    }
                    inFlight.remove(flightKey, mine);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(flightKey, mine);
            log.warn("필터 메뉴 계산 대기열이 찼다 — 이번 요청은 메뉴 없이 cache={}", cacheName);
            mine.completeExceptionally(e);
        }
        return mine;
    }

    private static List<Object> await(CompletableFuture<List<Object>> future, long deadlineNanos) {
        try {
            long left = Math.max(0, deadlineNanos - System.nanoTime());
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return List.of();   // 계산은 계속된다 — 다음 요청이 캐시에서 받는다
        } catch (ExecutionException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(List<Object> values) {
        return new ArrayList<>((List<T>) (List<?>) values);
    }
}
