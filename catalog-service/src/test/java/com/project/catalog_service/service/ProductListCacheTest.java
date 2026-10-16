package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.project.catalog_service.repository.jpa.data.ProductRepository;

/**
 * 상품 목록 · 상세 캐시(products) — 키가 섞이지 않고, 빈 결과는 남지 않고, 쓰기마다 비워진다.
 *
 * <p>🐛 예전:
 * <ul>
 *   <li>slug · 이름 · 'warmup' · 'all' · 카테고리 키가 한 공간에 섞였다 — 익명 {@code GET /api/product/warmup}(slug 조회)이
 *       관리자 목록의 'warmup' 키에 빈 목록을 영구로 박았다. 홈(4)과 관리자 목록(20)도 같은 'warmup' 을 썼다</li>
 *   <li>없는 slug · 카테고리의 빈 결과도 만료 없이 저장돼 익명 요청만으로 공유 Redis 를 채울 수 있었다</li>
 *   <li>상품 등록 · 색상 추가 · 별점 · 일괄 등록 · 카탈로그 초기화가 products 를 비우지 않아 새 상품 · 새 색상이 나오지 않았다</li>
 * </ul>
 *
 * <p>[JA] 商品一覧 · 詳細キャッシュ — キーが混ざらず、空の結果は残らず、書き込みのたびに消える。
 */
@SpringJUnitConfig(ProductListCacheTest.Config.class)
@DisplayName("상품 목록 캐시 — 키 · 빈 결과 · 무효화")
class ProductListCacheTest {

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
        com.project.catalog_service.repository.jpa.data.CategoryRepository categoryRepository() {
            return mock(com.project.catalog_service.repository.jpa.data.CategoryRepository.class);
        }

        @Bean
        ProductService productService(ProductRepository products,
                com.project.catalog_service.repository.jpa.data.CategoryRepository categories) {
            return new ProductService(categories,
                    mock(com.project.catalog_service.repository.jpa.data.SubcategoryRepository.class), products,
                    mock(com.project.catalog_service.repository.jpa.data.ProductSizeRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductSkuRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductColorRepository.class),
                    mock(com.project.catalog_service.repository.jpa.data.ProductQARepository.class),
                    mock(ImageService.class), mock(ProductIndexService.class), mock(EventPricing.class),
                    mock(com.project.catalog_service.message.producer.StockEventProducer.class));
        }
    }

    @Autowired ProductService service;
    @Autowired ProductRepository productRepository;
    @Autowired com.project.catalog_service.repository.jpa.data.CategoryRepository categoryRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clear() {
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        org.mockito.Mockito.reset(productRepository, categoryRepository);
    }

    @Test
    @DisplayName("🔴 없는 slug 의 빈 결과는 캐시하지 않는다 — 'warmup' 같은 slug 가 다른 메서드의 키를 덮지도 않는다")
    void emptySlugIsNotCached() {
        when(productRepository.findBySlug(any())).thenReturn(List.of());

        assertThat(service.getProductsBySlug("warmup")).isEmpty();
        assertThat(service.getProductsBySlug("warmup")).isEmpty();

        verify(productRepository, times(2)).findBySlug("warmup");
        assertThat(cacheManager.getCache("products").get("warmup")).isNull();
        assertThat(cacheManager.getCache("products").get("slug:warmup")).isNull();
    }

    @Test
    @DisplayName("🔴 홈(4)과 관리자 목록(20)의 warmup 은 다른 키 — 키에 pageSize 가 들어 있다")
    void warmupKeyDependsOnPageSize() throws Exception {
        String key = ProductService.class.getMethod("warmUpProductCaches", int.class).getAnnotation(Cacheable.class).key();
        assertThat(key).contains("#pageSize");
    }

    // ── 애노테이션 계약 ────────────────────────────────────────────────────

    private static List<Method> cacheableOn(String cache) {
        List<Method> out = new ArrayList<>();
        for (Method m : ProductService.class.getDeclaredMethods()) {
            Cacheable c = m.getAnnotation(Cacheable.class);
            if (c != null && (Arrays.asList(c.value()).contains(cache) || Arrays.asList(c.cacheNames()).contains(cache))) {
                out.add(m);
            }
        }
        return out;
    }

    @Test
    @DisplayName("🔴 products 의 키는 메서드마다 고유한 접두사로 시작한다 — 사용자가 정하는 값(slug · 이름)이 다른 키가 될 수 없다")
    void productsKeysArePrefixed() {
        Set<String> prefixes = new HashSet<>();
        List<Method> methods = cacheableOn("products");
        assertThat(methods).hasSizeGreaterThanOrEqualTo(6);
        for (Method m : methods) {
            String key = m.getAnnotation(Cacheable.class).key();
            assertThat(key).as(m.getName()).startsWith("'");
            String prefix = key.substring(1, key.indexOf('\'', 1));
            assertThat(prefix.equals("all") || prefix.endsWith(":")).as(m.getName() + " → " + prefix).isTrue();
            prefixes.add(prefix + "#" + m.getName());
        }
        assertThat(prefixes.stream().map(p -> p.substring(0, p.indexOf('#'))).distinct().count())
                .as("메서드마다 다른 접두사").isEqualTo(methods.size());
    }

    @Test
    @DisplayName("🔴 products 목록 캐시는 빈 결과를 남기지 않는다(unless)")
    void productsListsSkipEmpty() {
        for (Method m : cacheableOn("products")) {
            assertThat(m.getAnnotation(Cacheable.class).unless()).as(m.getName()).contains("isEmpty()");
        }
    }

    private static boolean evictsAllProducts(Method m) {
        List<CacheEvict> evicts = new ArrayList<>();
        if (m.getAnnotation(CacheEvict.class) != null) {
            evicts.add(m.getAnnotation(CacheEvict.class));
        }
        if (m.getAnnotation(Caching.class) != null) {
            evicts.addAll(Arrays.asList(m.getAnnotation(Caching.class).evict()));
        }
        return evicts.stream().anyMatch(e -> e.allEntries() && Arrays.asList(e.value()).contains("products"));
    }

    @Test
    @DisplayName("🔴 상품 · 색상 · 별점 · 일괄 등록 · 수정 · 상태 변경은 products 를 비운다")
    void productWritesEvictProducts() {
        Set<String> writes = Set.of("addProduct", "load", "updateRating", "updateProduct", "updateStatus");
        int checked = 0;
        for (Method m : ProductService.class.getDeclaredMethods()) {
            if (writes.contains(m.getName()) && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                assertThat(evictsAllProducts(m)).as(m.getName()).isTrue();
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(6);   // load 는 둘
    }

    @Test
    @DisplayName("🔴 카탈로그 초기화 · 가져오기 종료는 캐시를 비운다")
    void resetEvictsProducts() throws Exception {
        assertThat(evictsAllProducts(CatalogResetService.class.getMethod("reset"))).isTrue();
        assertThat(evictsAllProducts(CatalogResetService.class.getMethod("clearCaches"))).isTrue();
    }
}
