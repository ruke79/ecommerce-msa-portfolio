package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;

/**
 * 주문 품목의 SKU — 장바구니가 적어 둔 사이즈 한 줄의 재고 단위 코드(catalog V17)를 <b>주문 시점 값으로 복사</b>하고,
 * 주문 내역 · 배송(포장 목록)이 그 값을 그대로 받는다.
 */
@DisplayName("주문 품목 — SKU")
class OrderSkuCodeTest {

    private static Order order(OrderProduct... lines) {
        Order order = Order.builder().orderId(1L).trackingId("track-1").customerId(7L)
                .total(new BigDecimal("200")).orderStatus(OrderStatus.PAID).build();
        for (OrderProduct l : lines) {
            l.setOrder(order);
        }
        order.setOrderedProducts(new ArrayList<>(List.of(lines)));
        return order;
    }

    @Test
    @DisplayName("장바구니 줄의 SKU · 사이즈를 주문 품목에 복사한다")
    void copiesSkuFromCartLine() {
        CartProductDto line = CartProductDto.builder().id("786912345678901234").productId("10").name("러닝화")
                .size("270").skuCode("RUN-BLK-270").qty(2).price(new BigDecimal("100"))
                .color(ProductColorDto.builder().id("5").build()).build();

        OrderProduct op = OrderDataService.toOrderProduct(line, order());

        assertThat(op.getSkuCode()).isEqualTo("RUN-BLK-270");
        assertThat(op.getSize()).isEqualTo("270");
        assertThat(op.getProductId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("SKU 가 없던 줄(코드 이전 상품)도 주문은 만들어진다 — SKU 는 기록이지 조건이 아니다")
    void missingSkuDoesNotBlockTheOrder() {
        CartProductDto line = CartProductDto.builder().productId("10").name("책").size("").qty(1)
                .price(new BigDecimal("10")).color(ProductColorDto.builder().id("5").build()).build();

        assertThat(OrderDataService.toOrderProduct(line, order()).getSkuCode()).isNull();
    }

    @Test
    @DisplayName("배송에 넘기는 품목에 SKU 가 실린다 — 창고는 이름이 아니라 코드로 집는다")
    void shippingItemsCarrySku() {
        OrderProduct black = OrderProduct.builder().orderProductId(101L).productId(10L).colorId(5L).size("270")
                .skuCode("RUN-BLK-270").qty(1).price(new BigDecimal("100")).build();
        OrderProduct white = OrderProduct.builder().orderProductId(102L).productId(10L).colorId(6L).size("270")
                .skuCode("RUN-WHT-270").qty(1).price(new BigDecimal("100")).build();

        var dto = OrderShippingInfoService.toDto(order(black, white));

        // 같은 상품 · 같은 사이즈라도 색상이 다르면 다른 물건 — 코드가 그것을 구분한다
        assertThat(dto.items()).extracting(i -> i.skuCode()).containsExactly("RUN-BLK-270", "RUN-WHT-270");
    }

    @Test
    @DisplayName("🐛 주문 내역 응답에 사이즈가 비어 있었다 — 이제 사이즈와 SKU 를 싣는다")
    void orderDtoCarriesSizeAndSku() {
        OrderProduct op = OrderProduct.builder().orderProductId(101L).productId(10L).colorId(5L).size("270")
                .skuCode("RUN-BLK-270").qty(1).price(new BigDecimal("100")).build();

        var dto = OrderService.convertToOrderDto(order(op), Map.of());

        assertThat(dto.getProducts()).singleElement().satisfies(p -> {
            assertThat(p.getSize()).isEqualTo("270");
            assertThat(p.getSkuCode()).isEqualTo("RUN-BLK-270");
        });
    }
}
