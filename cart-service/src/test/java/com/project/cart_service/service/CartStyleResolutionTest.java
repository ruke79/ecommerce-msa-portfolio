package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.cart_service.model.CartProduct;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSkuDto;

/** 장바구니 줄의 스타일(SKU) — 저장된 목록 위치가 아니라 색상으로 찾는다. */
class CartStyleResolutionTest {

    private static ProductDto product(String... colorIds) {
        return ProductDto.builder().id("1")
                .skus(java.util.Arrays.stream(colorIds)
                        .map(c -> ProductSkuDto.builder().color(ProductColorDto.builder().id(c).build()).build())
                        .toList())
                .build();
    }

    private static CartProduct line(int style, Long colorId) {
        return CartProduct.builder().productId(1L).style(style).colorId(colorId).build();
    }

    @Test
    @DisplayName("저장된 위치의 색상이 같으면 그 위치")
    void storedPositionWhenColorMatches() {
        assertThat(CartService.styleIndexOf(line(1, 20L), product("10", "20", "30"))).isEqualTo(1);
    }

    @Test
    @DisplayName("SKU 목록이 바뀌어 위치가 어긋나면 색상으로 다시 찾는다 — 예전에는 다른 변형(색 · 가격)을 가리켰다")
    void followsColorWhenPositionShifted() {
        // 담을 때 [10, 20, 30] 의 1번(20). 그 뒤 10 이 빠져 [20, 30] — 1번은 이제 30 이다.
        assertThat(CartService.styleIndexOf(line(1, 20L), product("20", "30"))).isZero();
    }

    @Test
    @DisplayName("그 색상의 SKU 가 없어졌으면 -1 — 다른 변형으로 대신하지 않고 줄을 숨긴다")
    void missingColorIsUnresolvable() {
        assertThat(CartService.styleIndexOf(line(0, 20L), product("10", "30"))).isEqualTo(-1);
    }

    @Test
    @DisplayName("색상 id 가 없는 옛 줄은 저장된 위치를 그대로(범위 밖이면 -1)")
    void legacyLineUsesStoredPosition() {
        assertThat(CartService.styleIndexOf(line(1, null), product("10", "20"))).isEqualTo(1);
        assertThat(CartService.styleIndexOf(line(5, null), product("10", "20"))).isEqualTo(-1);
    }

    @Test
    @DisplayName("SKU 목록이 없으면 -1")
    void noSkus() {
        assertThat(CartService.styleIndexOf(line(0, 10L), ProductDto.builder().id("1").skus(List.of()).build())).isEqualTo(-1);
    }
}
