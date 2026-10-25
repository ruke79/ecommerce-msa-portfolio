package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.project.catalog_service.repository.jpa.data.ProductSizeRepository;
import com.project.catalog_service.repository.jpa.data.StockReservationRepository;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

/**
 * 목록·상세의 재고 = 지금 수량 − 다른 주문의 결제 전 예약 (docs/59 §5).
 */
@DisplayName("StockAvailability — 손님에게 보이는 재고")
class StockAvailabilityTest {

    StockReservationRepository reservations;
    ProductSizeRepository sizes;
    StockAvailability availability;

    @BeforeEach
    void setUp() {
        reservations = mock(StockReservationRepository.class);
        sizes = mock(ProductSizeRepository.class);
        availability = new StockAvailability(reservations, sizes);
        when(reservations.sumHeldByProducts(anyCollection(), any())).thenReturn(List.of());
        when(sizes.liveAndHeld(anyCollection(), any())).thenReturn(List.of());
    }

    /** 합친 질의의 '지금 수량' 행. */
    static Object[] live(long sizeId, int qty) {
        return new Object[] {"L", sizeId, null, null, null, (long) qty};
    }

    /** 합친 질의의 '예약 합' 행. */
    static Object[] held(long productId, long colorId, String size, long sum) {
        return new Object[] {"H", null, productId, colorId, size, sum};
    }

    private static ProductDto product(String id, String colorId, ProductSizeDto... sizes) {
        return ProductDto.builder().id(id).skus(List.of(ProductSkuDto.builder()
                .color(ProductColorDto.builder().id(colorId).build()).sizes(List.of(sizes)).build())).build();
    }

    private static ProductSizeDto size(String id, String size, int qty) {
        return ProductSizeDto.builder().id(id).size(size).quantity(qty).build();
    }

    private static ProductSizeDto sizeOf(ProductDto p, int i) {
        return p.getSkus().get(0).getSizes().get(i);
    }

    @Test
    @DisplayName("🔴 목록 캐시의 수량을 믿지 않는다 — 지금 수량으로 바꾸고, 그 위에서 남의 예약을 뺀다")
    void liveThenHolds() {
        ProductDto cached = product("1", "5", size("11", "M", 9), size("12", "L", 0));
        when(sizes.liveAndHeld(anyCollection(), any()))
                .thenReturn(List.<Object[]>of(live(11L, 2), live(12L, 4), held(1L, 5L, "M", 2L), held(1L, 5L, "L", 1L)));

        ProductDto shown = availability.apply(List.of(cached)).get(0);

        assertThat(sizeOf(shown, 0).getQuantity()).isZero();      // 지금 2, 둘 다 잡힘
        assertThat(sizeOf(shown, 0).getOnHold()).isEqualTo(2);
        assertThat(sizeOf(shown, 1).getQuantity()).isEqualTo(3);  // 캐시 0 → 지금 4, 1 잡힘
        assertThat(sizeOf(shown, 1).getOnHold()).isEqualTo(1);
        assertThat(sizeOf(cached, 0).getQuantity()).as("캐시된 DTO 는 그대로").isEqualTo(9);
    }

    @Test
    @DisplayName("🔴 한 페이지는 한 질의로 — 지금 수량과 예약 합을 따로 읽지 않는다, 상품마다 읽지 않는다(docs/83 §5)")
    void onePerPage() {
        availability.apply(List.of(product("1", "5", size("11", "M", 1)), product("2", "6", size("21", "M", 1)),
                product("3", "7", size("31", "M", 1))));

        verify(sizes, times(1)).liveAndHeld(eq(List.of(1L, 2L, 3L).stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))), any());
        verifyNoInteractions(reservations);
    }

    @Test
    @DisplayName("상세 한 건도 한 질의로")
    void detailIsOneQuery() {
        when(sizes.liveAndHeld(anyCollection(), any())).thenReturn(List.<Object[]>of(live(11L, 4), held(1L, 5L, "M", 1L)));

        ProductDto shown = availability.apply(product("1", "5", size("11", "M", 9)));

        assertThat(sizeOf(shown, 0).getQuantity()).isEqualTo(3);
        assertThat(sizeOf(shown, 0).getOnHold()).isEqualTo(1);
        verify(sizes, times(1)).liveAndHeld(anyCollection(), any());
        verifyNoInteractions(reservations);
    }

    @Test
    @DisplayName("못 읽으면 캐시 그대로 — 같은 객체를 돌려준다(수량 · 예약 모두)")
    void failsToCached() {
        when(sizes.liveAndHeld(anyCollection(), any())).thenThrow(new QueryTimeoutException("x"));
        ProductDto cached = product("1", "5", size("11", "M", 3));

        assertThat(availability.apply(List.of(cached)).get(0)).isSameAs(cached);
    }

    @Test
    @DisplayName("id 가 숫자가 아니거나 옵션이 비어 있어도 깨지지 않는다")
    void tolerant() {
        ProductDto odd = ProductDto.builder().id("x").build();
        ProductDto noSizes = ProductDto.builder().id("2").skus(List.of(ProductSkuDto.builder().build())).build();
        when(sizes.liveAndHeld(anyCollection(), any())).thenReturn(List.<Object[]>of(live(99L, 1)));

        List<ProductDto> shown = availability.apply(List.of(odd, noSizes));

        assertThat(shown.get(0)).isSameAs(odd);
        assertThat(shown.get(1).getSkus().get(0).getSizes()).isNull();
    }
}
