package com.project.catalog_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.project.catalog_service.dto.HomeDtos.HomeSectionRequest;
import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.catalog_service.service.HomeSectionService;
import com.project.catalog_service.service.HomeViewCache;
import com.project.catalog_service.service.StockAvailability;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 첫 화면은 캐시에서, 재고는 응답마다 — 그리고 관리자가 섹션을 바꾸면 이 파드의 캐시를 비운다(docs/83 §5).
 *
 * <p>[JA] トップ画面の構成はキャッシュから、在庫は応答ごと。管理者がセクションを変えたらこの Pod のキャッシュを消す。
 */
@DisplayName("첫 화면 캐시 — 공개 홈 · 관리자 섹션")
class HomeCacheControllerTest {

    final StockReservationRepository reservations = mock(StockReservationRepository.class);
    final ProductSizeRepository sizes = mock(ProductSizeRepository.class);
    final HomeSectionService sections = mock(HomeSectionService.class);

    private static ProductDto product(String id, String sizeId, int qty) {
        return ProductDto.builder().id(id).skus(List.of(ProductSkuDto.builder()
                .color(ProductColorDto.builder().id("5").build())
                .sizes(List.of(ProductSizeDto.builder().id(sizeId).size("M").quantity(qty).build())).build())).build();
    }

    private static int qty(HomeView home) {
        return home.sections().get(0).products().get(0).getSkus().get(0).getSizes().get(0).getQuantity();
    }

    @Test
    @DisplayName("🔴 홈 두 번 — 구성은 한 번만 만들고, 재고는 두 번 읽어 그때의 수량을 보인다(캐시된 값은 그대로)")
    void homeComesFromCacheButStockIsLive() {
        when(sections.home()).thenReturn(new HomeView(List.of(
                new HomeSectionView(1L, "신상", "grid", null, null, List.of(product("1", "11", 5))))));
        when(sizes.liveAndHeld(anyCollection(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] {"L", 11L, null, null, null, 4L}),
                        List.<Object[]>of(new Object[] {"L", 11L, null, null, null, 2L}));
        HomeViewCache cache = new HomeViewCache(sections, Duration.ofSeconds(30), new SimpleMeterRegistry());
        ContentController controller = new ContentController(null, cache, null, null, null,
                new StockAvailability(reservations, sizes));

        assertThat(qty(controller.home())).isEqualTo(4);
        assertThat(qty(controller.home())).as("재고는 캐시되지 않는다").isEqualTo(2);
        verify(sections, times(1)).home();
        verify(sizes, times(2)).liveAndHeld(anyCollection(), org.mockito.ArgumentMatchers.any());
        assertThat(qty(cache.get())).as("캐시에 담긴 원본은 고치지 않는다").isEqualTo(5);
    }

    @Test
    @DisplayName("🔴 관리자가 섹션을 만들거나 고치거나 지우면 — 저장한 뒤 이 파드의 첫 화면 캐시를 비운다 · 실패(400 · 404)면 그대로")
    void adminSectionChangesInvalidateAfterSaving() {
        HomeViewCache cache = mock(HomeViewCache.class);
        ContentAdminController admin = new ContentAdminController(null, null, sections, cache, null, null, null, null);
        HomeSectionRequest req = new HomeSectionRequest("신상", "CATEGORY", "상의", null, "GRID", 10, 0, true, null, null);

        admin.createHomeSection(req);
        admin.updateHomeSection(7L, req);
        admin.deleteHomeSection(7L);
        InOrder order = inOrder(sections, cache);
        order.verify(sections).create(req);
        order.verify(cache).invalidate();
        order.verify(sections).update(eq(7L), any());
        order.verify(cache).invalidate();
        order.verify(sections).delete(7L);
        order.verify(cache).invalidate();

        HomeViewCache untouched = mock(HomeViewCache.class);
        HomeSectionService failing = mock(HomeSectionService.class);
        when(failing.create(any())).thenThrow(new IllegalArgumentException("카테고리를 찾을 수 없습니다: x"));
        ContentAdminController bad = new ContentAdminController(null, null, failing, untouched, null, null, null, null);
        assertThat(bad.createHomeSection(req).getStatusCode().value()).isEqualTo(400);
        verify(untouched, never()).invalidate();
    }
}
