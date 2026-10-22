package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.ProductSkuRepository;

/**
 * 필터 메뉴는 카테고리마다 한 번만 DB 에 간다 — 캐시가 실제로 걸린다.
 *
 * <p>🐛 2026-10-19: 예전에는 이 메서드들이 {@code SearchService} 안에 있어 같은 클래스에서 직접 불렀다 — 스프링 프록시를
 * 거치지 않아 {@code @Cacheable} 이 한 번도 걸리지 않았다(요청마다 다시 계산, docs/83 §5). 따로 된 빈을 프록시로 띄워 센다.
 * 캐시 관리자는 이름만 같은 메모리 캐시(운영은 Redis).
 *
 * <p>[JA] フィルターメニューはカテゴリごとに一度だけ DB へ — キャッシュが本当に効く。以前は同じクラス内の直接呼び出しで効いていなかった。
 */
@DisplayName("필터 메뉴 캐시 — 카테고리마다 한 번")
class SearchFilterFacetsCacheTest {

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Config {
        @Bean ConcurrentMapCacheManager redisCacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean ProductRepository productRepository() { return mock(ProductRepository.class); }
        @Bean ProductSkuRepository productSkuRepository() { return mock(ProductSkuRepository.class); }
        @Bean ProductDetailsRepository productDetailsRepository() { return mock(ProductDetailsRepository.class); }
        @Bean SearchFilterFacets searchFilterFacets(ProductRepository p, ProductSkuRepository s, ProductDetailsRepository d) {
            return new SearchFilterFacets(p, s, d);
        }
    }

    @Test
    @DisplayName("🔴 같은 카테고리를 두 번 — 브랜드 · 색상 · 사이즈 · 상세 모두 DB 는 한 번(카테고리 없음 · 이름 검색도 따로 한 번)")
    void eachFacetHitsTheDatabaseOncePerCategory() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(ctx -> {
            ProductRepository products = ctx.getBean(ProductRepository.class);
            ProductSkuRepository skus = ctx.getBean(ProductSkuRepository.class);
            ProductDetailsRepository details = ctx.getBean(ProductDetailsRepository.class);
            when(products.findDistinctBrandsByCategoryId(7L)).thenReturn(List.of("a", "b"));
            when(products.findDistinctBrandsByCategoryName("shoes")).thenReturn(List.of("c"));
            when(skus.findColorsByCategoryId(7L)).thenReturn(List.of("Red"));
            when(skus.findAllColors()).thenReturn(List.of("Red", "Navy"));
            when(skus.findSizesByCategoryId(7L)).thenReturn(List.of("M"));
            when(details.findDistinctNameValuesByCategoryId(7L)).thenReturn(List.<Object[]>of(new Object[] { "Style", "Casual" }));

            SearchFilterFacets facets = ctx.getBean(SearchFilterFacets.class);
            for (int i = 0; i < 3; i++) {
                assertThat(facets.brands(7L)).containsExactly("a", "b");
                assertThat(facets.brandsByCategoryName("shoes")).containsExactly("c");
                assertThat(facets.colors(7L)).containsExactly("Red");
                assertThat(facets.colors(null)).containsExactly("Red", "Navy");
                assertThat(facets.sizes(7L)).containsExactly("M");
                assertThat(facets.details(7L)).singleElement()
                        .satisfies(d -> assertThat(d.getName() + "=" + d.getValue()).isEqualTo("Style=Casual"));
            }

            verify(products, times(1)).findDistinctBrandsByCategoryId(7L);
            verify(products, times(1)).findDistinctBrandsByCategoryName("shoes");
            verify(skus, times(1)).findColorsByCategoryId(7L);
            verify(skus, times(1)).findAllColors();
            verify(skus, times(1)).findSizesByCategoryId(7L);
            verify(details, times(1)).findDistinctNameValuesByCategoryId(7L);
        });
    }

    @Test
    @DisplayName("SearchService 는 메뉴를 그 빈에서 받는다 — 같은 클래스의 @Cacheable 메서드로 되돌아가지 않게")
    void searchServiceDelegatesToTheFacetBean() throws Exception {
        assertThat(SearchService.class.getDeclaredField("filterFacets").getType()).isEqualTo(SearchFilterFacets.class);
        for (var m : SearchService.class.getDeclaredMethods()) {
            var c = m.getAnnotation(org.springframework.cache.annotation.Cacheable.class);
            if (c != null) {
                assertThat(List.of(c.value())).as(m.getName() + " — 메뉴 캐시는 SearchFilterFacets 에")
                        .doesNotContain("colors_cache", "sizes_cache", "details_cache", "brands_cache");
            }
        }
    }
}
