package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.project.catalog_service.model.Product;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.common.dto.ProductDto;

/**
 * 별점 갱신은 그 상품의 캐시만 맞춘다 — 목록 · 검색 · 메뉴 캐시는 그대로(실제 Spring 캐시 프록시로 본다).
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5 · §2.2 mixed): 리뷰마다 부르는 {@code updateRating} 이 products · search_cache ·
 * products_by_category · 필터 메뉴 넷을 통째로 비웠다 — 리뷰 초당 1건에 모든 요청이 캐시 밖에서 DB 커넥션을 기다려 실패 0.25% → 79.90%.
 *
 * <p>[JA] 評価の更新はその商品のキャッシュだけを合わせる — 一覧 · 検索 · メニューのキャッシュはそのまま(本物の Spring キャッシュプロキシで見る)。
 */
@SpringJUnitConfig(RatingCacheTest.Config.class)
@DisplayName("별점 갱신 — 그 상품의 캐시만")
class RatingCacheTest {

    private static final long ID = 77L;

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        ProductRepository productRepository() {
            return mock(ProductRepository.class);
        }

        @Bean
        ProductService productService(ProductRepository products) {
            EventPricing pricing = mock(EventPricing.class);
            when(pricing.apply(any(ProductDto.class))).thenAnswer(i -> i.getArgument(0));
            return new ProductService(mock(com.project.catalog_service.repository.jpa.data.CategoryRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.SubcategoryRepository.class), products,
                    mock(com.project.catalog_service.repository.jpa.data.ProductSizeRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductSkuRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductColorRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductQARepository.class),
                    mock(ImageService.class), mock(ProductIndexService.class), pricing,
                    mock(com.project.catalog_service.message.producer.StockEventProducer.class));
        }
    }

    @Autowired ProductService service;
    @Autowired ProductRepository productRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void fill() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        Product product = Product.builder().productId(ID).name("Classic Hoodie 77").slug("classic-hoodie-77").status("ACTIVE").build();
        when(productRepository.updateRating(eq(ID), anyFloat())).thenReturn(1);
        when(productRepository.findById(ID)).thenReturn(Optional.of(product));

        // 측정 때처럼 모든 캐시가 차 있다
        put("products", "slug:classic-hoodie-77", List.of("old"));
        put("products", "name:Classic Hoodie 77", List.of("old"));
        put("products", "slug:another-product", List.of("other"));
        put("products", "cat:books_0_20", List.of("list"));
        put("products", "warmup:4", List.of("home"));
        put("search_cache", "category=3&page=1", "search");
        put("products_by_category", "3_0", "page");
        for (String menu : List.of("colors_cache", "sizes_cache", "details_cache", "brands_cache")) {
            put(menu, 3L, List.of("menu"));
        }
        put("product", ID, "old detail");
    }

    private void put(String cache, Object key, Object value) {
        cacheManager.getCache(cache).put(key, value);
    }

    private Object get(String cache, Object key) {
        var hit = cacheManager.getCache(cache).get(key);
        return hit == null ? null : hit.get();
    }

    @Test
    @DisplayName("🔴 목록 · 검색 · 카테고리 · 필터 메뉴 캐시는 그대로 — 예전: 일곱 캐시를 통째로 비워 리뷰 초당 1건에 실패 79.90%")
    void ratingKeepsListAndSearchCaches() {
        service.updateRating(ID, 4.5F);

        assertThat(get("search_cache", "category=3&page=1")).isEqualTo("search");
        assertThat(get("products_by_category", "3_0")).isEqualTo("page");
        assertThat(get("products", "cat:books_0_20")).isEqualTo(List.of("list"));
        assertThat(get("products", "warmup:4")).as("홈 카드").isEqualTo(List.of("home"));
        assertThat(get("products", "slug:another-product")).as("다른 상품의 상세").isEqualTo(List.of("other"));
        for (String menu : List.of("colors_cache", "sizes_cache", "details_cache", "brands_cache")) {
            assertThat(get(menu, 3L)).as(menu + " — 별점과 상관없다").isEqualTo(List.of("menu"));
        }
    }

    @Test
    @DisplayName("🔴 그 상품의 상세는 바로 맞는다 — id 상세는 새 값으로, slug · 이름 조회는 지운다(다음 조회가 DB 에서)")
    void ratingRefreshesThatProduct() {
        ProductDto dto = service.updateRating(ID, 4.5F);

        assertThat(get("product", ID)).as("id 상세는 새 값").isSameAs(dto);
        assertThat(get("products", "slug:classic-hoodie-77")).as("slug 상세").isNull();
        assertThat(get("products", "name:Classic Hoodie 77")).as("이름 조회").isNull();
    }
}
