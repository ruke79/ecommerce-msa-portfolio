package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.order.OrderShippingDto;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderAddress;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.repository.jpa.OrderRepository;

/** 배송 서비스가 읽는 주문의 단면 — 주문 쪽 필드 이름을 배송 쪽 이름으로 옮긴다. */
@DisplayName("order-service — 배송 단면 조회")
class OrderShippingInfoServiceTest {

    @Test
    @DisplayName("수취인은 이름 둘을 합치고, 주소·품목은 배송 쪽 이름으로 옮긴다")
    void mapsAddressAndItems() {
        OrderRepository repo = mock(OrderRepository.class);
        Order order = Order.builder().orderId(1L).customerId(7L).orderStatus(OrderStatus.PAID)
                .shippingAddress(OrderAddress.builder().firstname("길동").lastname("홍")
                        .address1("서울 강남구 테헤란로 1").address2("3층").city("서울").state("서울")
                        .zipCode("06236").country("KR").phoneNumber("010-1234-5678").build())
                .orderedProducts(List.of(
                        OrderProduct.builder().orderProductId(101L).productId(10L).name("티셔츠").image("/t.png").qty(2).build()))
                .build();
        when(repo.findById(1L)).thenReturn(Optional.of(order));

        OrderShippingDto dto = new OrderShippingInfoService(repo).find(1L).orElseThrow();

        assertThat(dto.customerId()).isEqualTo(7L);
        assertThat(dto.orderStatus()).isEqualTo("PAID");
        assertThat(dto.address().recipientName()).isEqualTo("길동 홍");
        assertThat(dto.address().line1()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(dto.address().postalCode()).isEqualTo("06236");
        assertThat(dto.address().recipientPhone()).isEqualTo("010-1234-5678");
        assertThat(dto.items()).singleElement().satisfies(i -> {
            assertThat(i.orderProductId()).isEqualTo(101L);
            assertThat(i.name()).isEqualTo("티셔츠");
            assertThat(i.quantity()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("주소가 없는 주문은 address 가 null 이고, 없는 주문은 비어 있다")
    void missingAddressAndMissingOrder() {
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(Order.builder().orderId(1L).customerId(7L).build()));
        when(repo.findById(2L)).thenReturn(Optional.empty());
        OrderShippingInfoService service = new OrderShippingInfoService(repo);

        assertThat(service.find(1L).orElseThrow().address()).isNull();
        assertThat(service.find(2L)).isEmpty();
    }
}
