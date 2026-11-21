package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;

/**
 * 재고 · 판매량만 바꾸는 길은 필터 메뉴 캐시를 지우지 않는다.
 *
 * <p>🐛 2026-11-21 운영 읽기 천장 A-1(docs/95 §5-1): 결제 확정 차감(productUpdated)마다 메뉴 캐시 넷을 통째로 지워 Redis 에 카테고리
 * 4 ~ 5개만 남았고, 첫 쪽 검색이 메뉴 계산(200만 상품)을 대기 상한 1.5초까지 기다렸다(p50 1.50초). 메뉴 질의는 재고를 보지 않는다 —
 * 그 전제도 여기서 지킨다(질의에 재고 열이 생기면 다시 지워야 한다).
 *
 * <p>[JA] 在庫 · 販売数だけを変える経路はフィルターメニューのキャッシュを消さない(本番天井測定 2026-11-21)。メニュー照会が在庫を見ないことも守る。
 */
@DisplayName("재고 경로 — 필터 메뉴 캐시는 그대로")
class StockCacheEvictionTest {

    private static final Set<String> MENU = Set.of("brands_cache", "colors_cache", "sizes_cache", "details_cache");
    private static final Set<String> STOCK_PATHS = Set.of("updateSoldValue", "updateProductSize_Qty", "setStock", "productUpdated");

    @Test
    @DisplayName("🔴 재고 경로 다섯(판매 수 · 수량 · 재고 정하기 · 결제 확정 차감 둘)은 결과 캐시만 지우고 메뉴 캐시 넷은 남긴다")
    void stockPathsKeepFacetCaches() {
        List<Method> paths = Arrays.stream(ProductService.class.getMethods())
                .filter(m -> STOCK_PATHS.contains(m.getName()) && m.isAnnotationPresent(CacheEvict.class)).toList();
        assertThat(paths).as("재고 경로의 @CacheEvict 다섯").hasSize(5);
        for (Method m : paths) {
            List<String> evicted = List.of(m.getAnnotation(CacheEvict.class).value());
            assertThat(evicted).as(m.getName() + " — 재고는 결과 캐시에 보인다").contains("search_cache");
            assertThat(evicted).as(m.getName() + " — 메뉴는 재고와 무관").doesNotContainAnyElementsOf(MENU);
        }
    }

    @Test
    @DisplayName("전제 — 메뉴 질의(브랜드 · 색상 · 사이즈 · 상세)는 재고 · 판매량 · 상태 열을 보지 않는다")
    void facetQueriesDoNotReadStock() throws Exception {
        Path repo = Path.of("src/main/java/com/project/catalog_service/repository/jpa/data");
        for (String[] q : new String[][] {
                {"ProductRepository.java", "findDistinctBrandsByCategoryId"},
                {"ProductSkuRepository.java", "findColorsByCategoryId"},
                {"ProductSkuRepository.java", "findSizesByCategoryId"},
                {"ProductDetailsRepository.java", "findDistinctNameValuesByCategoryId"}}) {
            String src = Files.readString(repo.resolve(q[0]), StandardCharsets.UTF_8);
            int at = src.indexOf(" " + q[1] + "(");
            assertThat(at).as(q[1] + " 가 " + q[0] + " 에 있다").isPositive();
            String query = src.substring(src.lastIndexOf("@Query", at), at).toLowerCase();
            assertThat(query).as(q[1] + " 의 질의").doesNotContain("quantity", "sold", "status", "on_hold");
        }
    }
}
