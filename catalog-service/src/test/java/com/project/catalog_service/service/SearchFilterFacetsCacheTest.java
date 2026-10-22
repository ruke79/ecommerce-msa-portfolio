package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDetailsDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 필터 메뉴 — 요청은 계산하지 않는다(캐시를 읽고, 없으면 실행기에 맡긴 뒤 짧게만 기다린다), 키마다 한 번, 오래되면 옛 값을 내주며 뒤에서 다시.
 *
 * <p>🐛 2026-10-20 실습(상품 1,000만): 메뉴 질의(사이즈 DISTINCT 76초)가 검색 요청 안에서 돌아 커넥션을 쥐었고, 캐시 만료마다 다시 돌아
 * 풀이 차서 home 까지 5xx 였다. 캐시 관리자는 이름만 같은 메모리 캐시(운영은 Redis — 왕복은 SearchFacetsTimeoutIntegrationTest).
 *
 * <p>[JA] フィルターメニュー — リクエストは計算しない、キーごとに一度、古ければ古い値を返しつつ裏で計算し直す。
 */
@DisplayName("필터 메뉴 — 요청 밖에서 계산 · 키마다 한 번 · 오래되면 뒤에서")
class SearchFilterFacetsCacheTest {

    static class Params extends SearchParamsRequest {
        Params(String category) {
            super();
            setCategory(category);
            setIncludeFilters(true);
        }
    }

    private static SearchResultDto empty() {
        return SearchResultDto.builder().colors(List.of()).sizes(List.of()).details(List.of()).brandsDB(List.of()).build();
    }

    private static FacetQueries queries() {
        FacetQueries q = mock(FacetQueries.class);
        when(q.brands(7L)).thenReturn(List.of("a", "b"));
        when(q.brandsByCategoryName("shoes")).thenReturn(List.of("c"));
        when(q.colors(7L)).thenReturn(List.of("Red"));
        when(q.colors(null)).thenReturn(List.of("Red", "Navy"));
        when(q.sizes(7L)).thenReturn(List.of("M"));
        when(q.sizes(null)).thenReturn(List.of("M", "L"));
        when(q.details(any())).thenReturn(List.of(ProductDetailsDto.builder().name("Style").value("Casual").build()));
        return q;
    }

    /** 바로 실행하는 실행기 · 바꿀 수 있는 시계 — 순서를 정해 두고 본다. */
    private static SearchFilterFacets facets(FacetQueries q, ConcurrentMapCacheManager caches, AtomicLong now) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        return new SearchFilterFacets(q, caches, Runnable::run, Duration.ofMinutes(10), Duration.ofMillis(500), clock);
    }

    @Test
    @DisplayName("🔴 같은 카테고리를 세 번 — 메뉴 넷 모두 질의는 한 번(카테고리 없음 · 이름 검색도 따로 한 번)")
    void eachFacetIsComputedOncePerCategory() {
        FacetQueries q = queries();
        SearchFilterFacets facets = facets(q, new ConcurrentMapCacheManager(), new AtomicLong(1_000));
        for (int i = 0; i < 3; i++) {
            SearchResultDto r = facets.attach(empty(), new Params("7"));
            assertThat(r.getBrandsDB()).containsExactly("a", "b");
            assertThat(r.getColors()).containsExactly("Red");
            assertThat(r.getSizes()).containsExactly("M");
            assertThat(r.getDetails()).singleElement().satisfies(d -> assertThat(d.getName() + "=" + d.getValue()).isEqualTo("Style=Casual"));
            assertThat(facets.attach(empty(), new Params("shoes")).getBrandsDB()).containsExactly("c");
            assertThat(facets.attach(empty(), new Params(null)).getSizes()).containsExactly("M", "L");
        }
        verify(q, times(1)).brands(7L);
        verify(q, times(1)).brandsByCategoryName("shoes");
        verify(q, times(1)).colors(7L);
        verify(q, times(1)).sizes(7L);
        verify(q, times(1)).sizes(null);
    }

    @Test
    @DisplayName("includeFilters=false 면 메뉴 질의를 맡기지 않는다")
    void noFiltersNoWork() {
        FacetQueries q = queries();
        Params p = new Params("7");
        p.setIncludeFilters(false);
        facets(q, new ConcurrentMapCacheManager(), new AtomicLong()).attach(empty(), p);
        verify(q, never()).sizes(any());
        verify(q, never()).brands(any());
    }

    @Test
    @DisplayName("🔴 오래된 값(10분+)은 그대로 내주고 다시 계산한다 — 만료 순간 요청이 질의를 기다리지 않는다")
    void staleValueIsServedAndRefreshed() {
        FacetQueries q = queries();
        AtomicLong now = new AtomicLong(1_000);
        SearchFilterFacets facets = facets(q, new ConcurrentMapCacheManager(), now);
        facets.attach(empty(), new Params("7"));
        when(q.sizes(7L)).thenReturn(List.of("M", "XL"));

        now.addAndGet(Duration.ofMinutes(9).toMillis());
        assertThat(facets.attach(empty(), new Params("7")).getSizes()).as("9분 — 아직 새 값").containsExactly("M");
        verify(q, times(1)).sizes(7L);

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        facets.attach(empty(), new Params("7"));   // 11분 — 다시 계산을 맡긴다(이 실행기는 바로 돈다)
        verify(q, times(2)).sizes(7L);
        assertThat(facets.attach(empty(), new Params("7")).getSizes()).containsExactly("M", "XL");
    }

    @Test
    @DisplayName("🔴 계산이 기다림 상한(0.5초)보다 길면 메뉴 없이 바로 — 계산은 이어지고 다음 요청이 받는다 · 동시 요청에도 질의는 한 번")
    void slowComputationDoesNotHoldTheRequest() throws Exception {
        FacetQueries q = queries();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(q.sizes(7L)).thenAnswer(i -> {
            calls.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return List.of("M");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SearchFilterFacets facets = new SearchFilterFacets(q, new ConcurrentMapCacheManager(), pool,
                    Duration.ofMinutes(10), Duration.ofMillis(500), Clock.systemUTC());
            long start = System.nanoTime();
            SearchResultDto first = facets.attach(empty(), new Params("7"));
            SearchResultDto second = facets.attach(empty(), new Params("7"));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(ms).as("두 요청이 기다린 시간 — 각자 상한(0.5초)까지만").isLessThan(2_500);
            assertThat(first.getSizes()).isEmpty();
            assertThat(second.getSizes()).isEmpty();
            assertThat(first.getColors()).as("다른 메뉴는 그대로").containsExactly("Red");

            release.countDown();
            List<String> later = List.of();
            for (int i = 0; i < 50 && later.isEmpty(); i++) {
                Thread.sleep(50);
                later = facets.attach(empty(), new Params("7")).getSizes();
            }
            assertThat(later).containsExactly("M");
            assertThat(calls.get()).as("진행 중인 계산을 같이 기다린다 — 키마다 하나").isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("계산이 실패하면 옛 값을 지우지 않는다 · 옛 모양(목록 그대로)의 캐시 값은 없는 것으로 보고 다시 만든다")
    void failureKeepsOldValueAndOldFormatIsRecomputed() {
        FacetQueries q = queries();
        AtomicLong now = new AtomicLong(1_000);
        ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager();
        SearchFilterFacets facets = facets(q, caches, now);
        facets.attach(empty(), new Params("7"));
        when(q.sizes(7L)).thenThrow(new RuntimeException("canceling statement due to statement timeout"));
        now.addAndGet(Duration.ofMinutes(11).toMillis());
        assertThat(facets.attach(empty(), new Params("7")).getSizes()).containsExactly("M");
        assertThat(facets.attach(empty(), new Params("7")).getSizes()).as("실패 뒤에도 옛 값").containsExactly("M");

        caches.getCache("colors_cache").put("7", new ArrayList<>(List.of("Old")));   // 2026-10-20 전의 모양
        assertThat(facets.attach(empty(), new Params("7")).getColors()).containsExactly("Red");
    }

    @Test
    @DisplayName("SearchService 는 메뉴를 만들지 않는다 — 캐시된 검색 결과에 메뉴 캐시 이름이 섞이지 않는다")
    void searchServiceDoesNotBuildFacets() {
        assertThat(java.util.Arrays.stream(SearchService.class.getDeclaredFields()).map(f -> f.getType()))
                .doesNotContain((Class) SearchFilterFacets.class, (Class) FacetQueries.class);
        for (var m : SearchService.class.getDeclaredMethods()) {
            var c = m.getAnnotation(org.springframework.cache.annotation.Cacheable.class);
            if (c != null) {
                assertThat(List.of(c.value())).as(m.getName()).doesNotContain("colors_cache", "sizes_cache", "details_cache", "brands_cache");
            }
        }
    }
}
