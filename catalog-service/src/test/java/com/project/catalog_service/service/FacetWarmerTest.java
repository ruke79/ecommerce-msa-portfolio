package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import com.project.catalog_service.dto.SearchResultDto;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.common.dto.catalog.request.SearchParamsRequest;

/**
 * 필터 메뉴 미리 채우기 — 모든 카테고리의 메뉴 넷을 손님보다 먼저, 새것은 건너뛰고, 한 카테고리의 실패가 나머지를 막지 않는다.
 *
 * <p>🐛 2026-11-21 운영 읽기 천장(docs/95 §5-1): 메뉴 계산(카테고리마다 약 100초)을 첫 방문이 맡아 그동안 첫 쪽 검색이 1.5초를 기다렸다.
 * [JA] フィルターメニューの事前計算 — 全カテゴリを客より先に、新しいものは飛ばし、一つの失敗が残りを止めない。
 */
@DisplayName("필터 메뉴 미리 채우기")
class FacetWarmerTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager();
    private final FacetQueries q = mock(FacetQueries.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);

    private SearchFilterFacets facets() {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        return new SearchFilterFacets(q, caches, Runnable::run, Duration.ofHours(6), Duration.ofMillis(500), clock);
    }

    private static SearchResultDto empty() {
        return SearchResultDto.builder().colors(List.of()).sizes(List.of()).details(List.of()).brandsDB(List.of()).build();
    }

    @Test
    @DisplayName("🔴 모든 카테고리의 메뉴 넷을 채운다 — 그 뒤 첫 방문은 기다리지 않고 메뉴가 있다 · 다시 돌면 새것이라 질의 0")
    void warmsEveryCategoryThenSkipsFreshOnes() {
        when(categories.findAllCategoryIds()).thenReturn(List.of(1L, 2L));
        when(q.brands(1L)).thenReturn(List.of("a"));
        when(q.colors(2L)).thenReturn(List.of("Red"));
        SearchFilterFacets f = facets();
        FacetWarmer warmer = new FacetWarmer(f, categories, 5);

        warmer.warmAll();
        for (long id : new long[] {1L, 2L}) {
            verify(q).brands(id);
            verify(q).colors(id);
            verify(q).sizes(id);
            verify(q).details(id);
        }
        SearchParamsRequest p = new SearchParamsRequest() { };
        p.setCategory("2");
        p.setIncludeFilters(true);
        assertThat(f.attach(empty(), p).getColors()).as("채워 둔 메뉴").containsExactly("Red");

        warmer.warmAll();
        verify(q, times(1)).brands(1L);
        verify(q, times(1)).sizes(2L);

        now.addAndGet(Duration.ofHours(7).toMillis());
        warmer.warmAll();
        verify(q, times(2)).brands(1L);
    }

    @Test
    @DisplayName("한 카테고리의 질의가 실패해도 나머지 카테고리는 채운다")
    void oneFailureDoesNotStopTheRest() {
        when(categories.findAllCategoryIds()).thenReturn(List.of(1L, 2L));
        when(q.sizes(1L)).thenThrow(new IllegalStateException("db"));
        new FacetWarmer(facets(), categories, 5).warmAll();
        verify(q).details(2L);
        verify(q).sizes(2L);
    }
}
