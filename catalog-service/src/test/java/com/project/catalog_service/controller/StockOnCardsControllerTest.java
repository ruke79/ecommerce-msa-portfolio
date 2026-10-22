package com.project.catalog_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.project.catalog_service.dto.HomeDtos.HomeSectionView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.catalog_service.service.HomeSectionService;
import com.project.catalog_service.service.HomeViewCache;
import com.project.catalog_service.service.SearchService;
import com.project.catalog_service.service.StockAvailability;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 목록 카드(홈·검색)의 재고 — 캐시된 목록에 응답 직전 지금 수량과 예약을 반영한다 (docs/59 §5).
 */
@DisplayName("목록 카드의 재고 — 홈·검색")
class StockOnCardsControllerTest {

    final StockReservationRepository reservations = mock(StockReservationRepository.class);
    final ProductSizeRepository sizes = mock(ProductSizeRepository.class);
    final StockAvailability availability = new StockAvailability(reservations, sizes);

    private static ProductDto product(String id, String sizeId, int qty) {
        return ProductDto.builder().id(id).skus(List.of(ProductSkuDto.builder()
                .color(ProductColorDto.builder().id("5").build())
                .sizes(List.of(ProductSizeDto.builder().id(sizeId).size("M").quantity(qty).build())).build())).build();
    }

    private static int qty(ProductDto p) {
        return p.getSkus().get(0).getSizes().get(0).getQuantity();
    }

    @Test
    @DisplayName("홈 — 섹션이 여럿이어도 한 번 읽고, 섹션마다 반영한다")
    void home() {
        HomeSectionService homeSections = mock(HomeSectionService.class);
        when(homeSections.home()).thenReturn(new HomeView(List.of(
                new HomeSectionView(1L, "신상", "grid", null, null, List.of(product("1", "11", 5))),
                new HomeSectionView(2L, "인기", "swiper", null, null, List.of(product("1", "11", 5), product("2", "21", 5))))));
        when(sizes.liveQuantities(anyCollection())).thenReturn(List.<Object[]>of(new Object[] {11L, 1}, new Object[] {21L, 0}));
        when(reservations.sumHeldByProducts(anyCollection(), any())).thenReturn(List.<Object[]>of(new Object[] {1L, 5L, "M", 1L}));
        ContentController controller = new ContentController(null,
                new HomeViewCache(homeSections, Duration.ZERO, new SimpleMeterRegistry()), null, null, null, availability);

        HomeView home = controller.home();

        assertThat(qty(home.sections().get(0).products().get(0))).isZero();
        assertThat(home.sections().get(0).products().get(0).getSkus().get(0).getSizes().get(0).getOnHold()).isEqualTo(1);
        assertThat(qty(home.sections().get(1).products().get(1))).isZero();
        assertThat(home.sections().get(1).title()).isEqualTo("인기");
        verify(sizes, times(1)).liveQuantities(anyCollection());
        verify(reservations, times(1)).sumHeldByProducts(anyCollection(), any());
    }

    @Test
    @DisplayName("검색 — 페이지 정보(전체 수·페이지)는 그대로, 상품만 반영한 복사본. 캐시된 결과는 그대로")
    void search() {
        SearchService search = mock(SearchService.class);
        ProductDto cached = product("1", "11", 5);
        SearchResultDto result = SearchResultDto.builder().totalProducts(41)
                .product(new PageImpl<>(List.of(cached), PageRequest.of(2, 20), 41)).build();
        when(search.searchProducts(any(), any())).thenReturn(result);
        when(sizes.liveQuantities(anyCollection())).thenReturn(List.<Object[]>of(new Object[] {11L, 2}));
        when(reservations.sumHeldByProducts(anyCollection(), any())).thenReturn(List.of());
        com.project.catalog_service.service.SearchFilterFacets facets = mock(com.project.catalog_service.service.SearchFilterFacets.class);
        when(facets.attach(any(), any())).thenAnswer(i -> i.getArgument(0));
        SearchController controller = new SearchController(search, availability, null, null, null, facets,
                mock(com.project.catalog_service.service.SearchEngineSelector.class));

        SearchResultDto shown = (SearchResultDto) controller.searchProducts(mock(SearchParamsRequest.class)).getBody();

        assertThat(qty(shown.getProduct().getContent().get(0))).isEqualTo(2);
        assertThat(shown.getProduct().getTotalElements()).isEqualTo(41);
        assertThat(shown.getProduct().getNumber()).isEqualTo(2);
        assertThat(shown.getTotalProducts()).isEqualTo(41);
        assertThat(qty(cached)).isEqualTo(5);
        assertThat(result.getProduct().getContent().get(0)).isSameAs(cached);
    }
}
