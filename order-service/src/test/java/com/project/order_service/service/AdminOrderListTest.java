package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.dto.order.AdminOrderSummaryDto;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.model.Order;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.payment.PaymentGateways;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderRepository;

/** 관리자 주문 목록 — 환불 화면이 읽는 것. */
@DisplayName("관리자 주문 목록")
class AdminOrderListTest {

    private OrderRepository orderRepository;
    private com.project.order_service.client.UserServiceClient userServiceClient;
    private OrderDataService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        userServiceClient = mock(com.project.order_service.client.UserServiceClient.class);
        service = new OrderDataService(orderRepository,
                mock(CatalogServiceClient.class), new ObjectMapper(),
                mock(OrderAddressRepository.class), mock(PaymentOutboxHelper.class),
                new PaymentGateways(List.of()),
                new com.project.order_service.payment.PaymentCurrencyPolicy());
    }

    private static Order order(long id, long customerId, OrderStatus status) {
        return Order.builder().orderId(id).trackingId("track-" + id).customerId(customerId)
                .total(new java.math.BigDecimal("100.00")).orderStatus(status).paymentProvider(PaymentProvider.STRIPE)
                .providerPaymentId("cs_" + id).build();
    }

    @Test
    @DisplayName("고객 이메일을 user-service 에 한 번 물어 붙인다 — 주문마다 묻지 않는다 (docs/48 §6-①)")
    void joinsCustomerEmailsInOneQuery() {
        when(orderRepository.findAllByOrderByOrderIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        order(2, 7, OrderStatus.PAID), order(1, 8, OrderStatus.REFUNDED))));
        when(userServiceClient.findEmailsByIds(any()))
                .thenReturn(java.util.Map.of("7", "a@example.com", "8", "b@example.com"));

        Page<AdminOrderSummaryDto> page = service.listOrdersForAdmin(null, null, 0, 20);
        assertThat(page.getContent()).extracting(AdminOrderSummaryDto::getCustomerEmail)
                .as("트랜잭션 안의 목록은 이메일을 채우지 않는다").containsOnlyNulls();
        OrderService.attachCustomerEmails(userServiceClient, page.getContent());

        assertThat(page.getContent()).extracting(AdminOrderSummaryDto::getCustomerEmail)
                .containsExactly("a@example.com", "b@example.com");
        assertThat(page.getContent().get(1).getOrderStatus()).isEqualTo("REFUNDED");
        verify(userServiceClient).findEmailsByIds(List.of(7L, 8L));
    }

    @Test
    @DisplayName("상태로 거른다 — 이름은 대소문자를 가리지 않는다")
    void filtersByStatus() {
        when(orderRepository.findByOrderStatusOrderByOrderIdDesc(eq(OrderStatus.PAID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order(1, 7, OrderStatus.PAID))));
        when(userServiceClient.findEmailsByIds(any())).thenReturn(java.util.Map.of());

        Page<AdminOrderSummaryDto> page = service.listOrdersForAdmin("paid", null, 0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCustomerEmail()).isNull();
    }

    /** 반품 대기는 OrderStatus 가 아니라 플래그다 — 상태 자리의 가짜 이름 하나로 고른다. */
    @Test
    @DisplayName("AWAITING_RETURN 은 반품 대기 플래그로 거른다")
    void awaitingReturnFilter() {
        Order waiting = order(3, 7, OrderStatus.REFUNDED);
        waiting.setAwaitingReturn(true);
        when(orderRepository.findByAwaitingReturnTrueOrderByOrderIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(waiting)));
        when(userServiceClient.findEmailsByIds(any())).thenReturn(java.util.Map.of());

        Page<AdminOrderSummaryDto> page = service.listOrdersForAdmin("awaiting_return", null, 0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).isAwaitingReturn()).isTrue();
        verify(orderRepository, never()).findByOrderStatusOrderByOrderIdDesc(any(), any());
    }

    /** 모르는 상태를 조용히 전체로 바꾸면 "필터가 안 먹는다" 가 된다(docs/50). 거부한다. */
    @Test
    @DisplayName("모르는 상태 이름은 거부한다 — 조용히 전체를 주지 않는다")
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> service.listOrdersForAdmin("WHATEVER", null, 0, 20))
                .isInstanceOf(IllegalStateException.class);
        verify(orderRepository, never()).findAllByOrderByOrderIdDesc(any());
    }

    @Test
    @DisplayName("추적번호가 있으면 그 주문 하나만 — 상태 필터보다 우선")
    void trackingIdWins() {
        when(orderRepository.findByTrackingId("track-5")).thenReturn(Optional.of(order(5, 7, OrderStatus.DISPATCHED)));
        when(userServiceClient.findEmailsByIds(any())).thenReturn(java.util.Map.of("7", "a@example.com"));

        Page<AdminOrderSummaryDto> page = service.listOrdersForAdmin("PAID", " track-5 ", 0, 20);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTrackingId()).isEqualTo("track-5");
        verify(orderRepository, never()).findByOrderStatusOrderByOrderIdDesc(any(), any());
    }

    @Test
    @DisplayName("🔴 user-service 가 실패해도 목록은 나온다 — 이메일만 빈다")
    void emailLookupFailureDegrades() {
        when(orderRepository.findAllByOrderByOrderIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order(1, 7, OrderStatus.PAID))));
        when(userServiceClient.findEmailsByIds(any())).thenThrow(new RuntimeException("user-service down"));

        Page<AdminOrderSummaryDto> page = service.listOrdersForAdmin(null, null, 0, 20);
        OrderService.attachCustomerEmails(userServiceClient, page.getContent());

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getCustomerEmail()).isNull();
    }

    @Test
    @DisplayName("페이지 크기는 100 을 넘지 않고, 음수 페이지는 0 이다")
    void pageIsClamped() {
        when(orderRepository.findAllByOrderByOrderIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listOrdersForAdmin(null, null, -3, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAllByOrderByOrderIdDesc(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }
}
