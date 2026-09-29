package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.common.dto.order.OrderDto;

/**
 * 주문 조회의 색상은 꾸밈이다 — catalog 가 실패해도 주문은 보인다.
 *
 * <p>🐛 예전에는 색상 조회 예외가 주문 조회를 통째로 400 으로 만들었다(catalog 재배포 중에 주문 화면이 깨졌다).
 */
@DisplayName("주문 조회 — catalog 실패에도 주문은 보인다")
class OrderColorsFallbackTest {

    @Test
    @DisplayName("🔴 색상 조회가 실패하면 색상 없이 변환한다")
    void catalogFailureStillShowsOrder() {
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        when(catalog.getColorsInfo(anyList())).thenThrow(new RuntimeException("catalog down"));
        Order order = Order.builder().orderId(7L).orderStatus(OrderStatus.NOT_PROCESSED).build();
        order.setOrderedProducts(List.of(OrderProduct.builder().orderProductId(1L).productId(10L).colorId(5L)
                .size("").qty(1).name("Italian Olive Oil").build()));

        OrderDto dto = OrderService.convertToOrderDto(order, null, catalog, null);

        assertThat(dto.getId()).isEqualTo("7");
        assertThat(dto.getProducts()).singleElement().satisfies(p -> {
            assertThat(p.getName()).isEqualTo("Italian Olive Oil");
            assertThat(p.getColor()).isNull();
        });
    }
}
