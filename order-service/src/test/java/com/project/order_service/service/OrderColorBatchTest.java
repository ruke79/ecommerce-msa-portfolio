package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.order.OrderDto;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/**
 * 주문 목록 한 페이지가 catalog 색상 조회를 <b>페이지당 한 번</b>만 하는지.
 *
 * <p>예전에는 주문 상품마다 {@code getColorInfo} 였다 — 주문 10건 × 상품 3개 = Feign 30번.
 * 배치로 바꾼 뒤 누군가 편의상 단건 호출로 되돌리면 컴파일도 기능도 그대로다. 횟수를
 * 세어야만 보인다.
 */
@DisplayName("주문 목록 · 색상 배치 조회")
class OrderColorBatchTest {

    private static Order order(long id, long... colorIds) {
        Order.OrderBuilder<?, ?> b = Order.builder().orderId(id).orderStatus(OrderStatus.PAID);
        java.util.List<OrderProduct> products = new java.util.ArrayList<>();
        for (int i = 0; i < colorIds.length; i++) {
            products.add(OrderProduct.builder().orderProductId(id * 10 + i).colorId(colorIds[i]).name("p").qty(1).build());
        }
        return b.orderedProducts(products).build();
    }

    @Test
    @DisplayName("주문 두 건(상품 셋, 색상 둘) → getColorsInfo 한 번, 중복 없는 id 로; 단건 getColorInfo 0번")
    void pageOfOrders_fetchesColorsOnce() {
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        when(catalog.getColorsInfo(List.of(1L, 2L))).thenReturn(ResponseEntity.ok(List.of(
                ProductColorDto.builder().id("1").color("red").build(),
                ProductColorDto.builder().id("2").color("blue").build())));

        OrderRepository orderRepository = mock(OrderRepository.class);
        List<Order> orders = List.of(order(1L, 1L, 1L), order(2L, 2L));
        when(orderRepository.findByCustomerId(eq(7L), any())).thenReturn(new PageImpl<>(orders));

        // 생성자 인자 순서는 @RequiredArgsConstructor 가 만드는 필드 선언 순서를 따른다(OrderOwnershipTest 와 동일).
        OrderDataService data = new OrderDataService(
                orderRepository,
                new ObjectMapper(),
                mock(OrderAddressRepository.class),
                mock(com.project.order_service.outbox.PaymentOutboxHelper.class),
                mock(com.project.order_service.payment.PaymentGateways.class),
                new com.project.order_service.payment.PaymentCurrencyPolicy());

        // 색상은 조회 트랜잭션 밖(OrderCacheService)에서 붙인다 — 데이터 서비스는 catalog 를 갖지 않는다.
        Page<OrderDto> page = new OrderCacheService(data, catalog).getOrdersByCustomerIdWithPagination(7L, 0, 10);

        // 왕복 횟수 — 이 테스트의 존재 이유.
        verify(catalog, times(1)).getColorsInfo(List.of(1L, 2L));
        verify(catalog, never()).getColorInfo(anyString());

        // 색상이 각 상품에 제대로 붙었는가.
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getProducts()).hasSize(2);
        assertThat(page.getContent().get(0).getProducts().get(0).getColor().getColor()).isEqualTo("red");
        assertThat(page.getContent().get(1).getProducts().get(0).getColor().getColor()).isEqualTo("blue");
    }

    @Test
    @DisplayName("catalog 가 모르는 색상은 예전처럼 null 로 남고 변환은 실패하지 않는다")
    void unknownColor_staysNull() {
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        when(catalog.getColorsInfo(List.of(9L))).thenReturn(ResponseEntity.ok(List.of()));

        OrderDto dto = OrderService.convertToOrderDto(order(1L, 9L), new ObjectMapper(), catalog, mock(OrderAddressRepository.class));

        assertThat(dto.getProducts()).hasSize(1);
        assertThat(dto.getProducts().get(0).getColor()).isNull();
    }
}
