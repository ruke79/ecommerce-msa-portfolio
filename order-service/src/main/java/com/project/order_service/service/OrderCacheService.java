package com.project.order_service.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.dto.CartDto;
import com.project.common.dto.CartProductDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.order.OrderAddressDto;
import com.project.common.dto.order.OrderDto;
import com.project.common.dto.order.OrderedProductDto;
import com.project.common.dto.order.request.OrderRequest;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.client.CartServiceClient;
import com.project.order_service.client.CatalogServiceClient;
import com.project.order_service.mapper.OrderAddressMapper;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderAddress;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.repository.jpa.OrderAddressRepository;
import com.project.order_service.repository.jpa.OrderProductRepository;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCacheService {

    private final OrderDataService orderDataService;
    
    // @Cacheable: customerId를 키로 사용하여 주문 목록을 캐시합니다.
    @Cacheable(value = "orders", key = "#customerId")
    @Transactional(readOnly = true)
    public List<Order> getOrdersByCustomerId(Long customerId) {
       return orderDataService.getOrdersByCustomerId(customerId);
    }

    /**
     * 새 주문 저장 + 관련 캐시 무효화.
     *
     * <p>⚠️ <b>여기서부터 트랜잭션이다.</b> 장바구니와 금액은 이미 정해진 값으로 받는다 —
     * 원격 호출은 {@code OrderService.createOrder} 가 트랜잭션 밖에서 끝냈다(docs/37 #5).
     * 이 자리에 Feign 호출을 다시 넣으면 DB 커넥션을 쥔 채 다른 서비스를 기다리게 된다.
     */
    @Transactional
    @CacheEvict(value = {"orders"}, allEntries = true)
    public Order createOrder(OrderRequest request, CartDto latestCart, OrderPricing.Amounts amounts,
            Long customerId) {
        return orderDataService.createOrder(request, latestCart, amounts, customerId);
    }

    // 키에 요청자 id 가 들어가는 이유: 소유권 검사가 결과를 가른다 — 남의 요청이 내 캐시 항목을 받으면 안 된다.
    @Cacheable(value = "order", key = "{#orderId, #requesterId, #filter}")
    @Transactional(readOnly = true)
    public OrderDto getOrder(Long orderId, Long requesterId, String filter) {
       return orderDataService.getOrder(orderId, requesterId, filter);
    }

     @Transactional(readOnly = true)
    @Cacheable(value = "orderList", key = "{#customerId, #filter}")
    public List<OrderDto> getOrders(Long customerId, String filter) {
        return orderDataService.getOrders(customerId, filter);
    }

    /**
     * 주문 목록 — 커서 페이징.
     *
     * <p><b>여기만 @Cacheable 이 없다.</b> 커서와 필터 조합마다 키가 갈려 적중률이 거의
     * 없고(스크롤 한 번에 새 키가 하나씩 생긴다), 주문 목록은 방금 한 주문이 보여야 하는
     * 화면이다. 바로 위 {@code orderList} 캐시가 {@code {email, filter}} 하나로 <b>전체
     * 목록</b>을 담고 있던 것이 원래 문제의 일부이기도 하다.
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersPage(Long customerId, String filter, Long cursor, int size) {
        return orderDataService.getOrdersPage(customerId, filter, cursor, size);
    }

         // 페이지별로 주문 목록을 조회하는 새로운 메서드
    @Cacheable(value = "ordersPage", key = "{#customerId, #page, #size}")
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByCustomerIdWithPagination(Long customerId, int page, int size) {
        return orderDataService.getOrdersByCustomerIdWithPagination(customerId, page, size);
    }

        // --- 점진적 로딩(커서 기반 페이징) 메서드 추가 ---
    /**
     * 고객 ID로 주문 목록을 커서 기반으로 조회합니다 (점진적 로딩).
     * @param customerId 조회할 고객의 ID
     * @param cursorId 마지막으로 조회한 주문의 ID (첫 페이지 조회 시 null)
     * @param size 페이지당 주문 개수
     * @return OrderDto 목록과 다음 커서 ID
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "ordersCursor", key = "{#customerId, #cursorId, #size}")
    public Page<OrderDto> getOrdersByCustomerIdWithCursor(Long customerId, Long cursorId, int size) {
        return orderDataService.getOrdersByCustomerIdWithCursor(customerId, cursorId, size);
    }
    

    // 결제 완료 처리 메서드
    // @CacheEvict: 결제 완료 시, 관련 캐시(orders, orderList)를 무효화합니다.
    /**
     * 확인된 결제를 반영한다 — 제공자와 무관하게. 규칙은 OrderDataService 가 갖는다.
     *
     * <p>캐시를 비우는 범위가 넓은 이유: 결제가 반영되면 주문 상태가 바뀌고, 목록·상세·
     * 페이지 캐시가 모두 옛 상태를 들고 있다. 결제했는데 화면이 미결제로 보이면
     * 사용자는 다시 결제한다.
     */
    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"},
            allEntries = true)
    public void recordPayment(String trackingId, PaymentProvider provider, String providerPaymentId,
            Long requesterId) {
        orderDataService.recordPayment(trackingId, provider, providerPaymentId, requesterId);
    }

    /** 주문 취소. 목록·상세 캐시가 옛 상태를 계속 보여 주면 취소가 안 된 것처럼 보인다. */
    @Transactional
    /** 추적 번호로 주문 — 캐시 없이(방금 바뀐 상태를 봐야 한다). */
    public Order getOrderByTrackingId(String trackingId) {
        return orderDataService.getOrderByTrackingId(trackingId);
    }

    /** 재고가 모자라 성립하지 않은 주문을 지운다(docs/59). */
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public boolean discardUnplaced(Long orderId) {
        return orderDataService.discardUnplaced(orderId);
    }

    /** 재고 예약의 만료 시각(docs/59). */
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public void recordStockHold(Long orderId, java.time.LocalDateTime holdUntil) {
        orderDataService.recordStockHold(orderId, holdUntil);
    }

    /** 같은 고객의 앞선 결제 전 주문의 예약을 새 주문에 넘긴다(docs/59 §4). */
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public java.util.List<Long> supersedeStockHolds(Long customerId, Long keepOrderId, java.time.LocalDateTime now) {
        return orderDataService.supersedeStockHolds(customerId, keepOrderId, now);
    }

    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public void cancelPaypalPayment(PaypalPaymentRequest request, Long requesterId) {
        orderDataService.cancelPaypalPayment(request, requesterId);
    }

    /**
     * 관리자 목록. <b>캐시하지 않는다</b> — 환불 직후 관리자가 보는 화면이 옛 상태를
     * 보여 주면 같은 주문을 또 환불하려 든다(서버는 멱등하지만 화면이 거짓말한다).
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<com.project.common.dto.order.AdminOrderSummaryDto>
            listOrdersForAdmin(String status, String trackingId, int page, int size) {
        return orderDataService.listOrdersForAdmin(status, trackingId, page, size);
    }

    /** 환불. 규칙은 OrderDataService 가 갖는다. 캐시를 비우는 이유는 결제 반영과 같다. */
    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order refundOrder(String trackingId, Long requesterId,
            boolean admin, String reason,
            java.util.List<com.project.common.dto.request.OrderItemQtyRequest> items) {
        return orderDataService.refundOrder(trackingId, requesterId, admin, reason, items);
    }

    // ── 무통장입금 (docs/49 §11) — 상태가 바뀌므로 결제 반영과 같은 범위를 비운다 ──

    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order startBankTransfer(String trackingId, Long requesterId,
            String depositorName, int deadlineHours) {
        return orderDataService.startBankTransfer(trackingId, requesterId, depositorName, deadlineHours);
    }

    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order registerVirtualAccount(String trackingId, String paymentRef,
            com.project.order_service.payment.portone.VirtualAccount va, Long requesterId, int fallbackDeadlineHours) {
        return orderDataService.registerVirtualAccount(trackingId, paymentRef, va, requesterId, fallbackDeadlineHours);
    }

    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order confirmDeposit(String trackingId, String depositReference) {
        return orderDataService.confirmDeposit(trackingId, depositReference);
    }

    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order cancelUnpaid(String trackingId, Long requesterId, boolean admin) {
        return orderDataService.cancelUnpaid(trackingId, requesterId, admin);
    }

    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public List<com.project.order_service.model.Order> expireUnpaid(LocalDateTime now) {
        return orderDataService.expireUnpaid(now);
    }

    /** 반품 입고. 고객 상세가 "반품 접수 중" 을 보여 주므로 상세 캐시를 비운다. */
    @Transactional
    @CacheEvict(value = {"orders", "orderList", "order", "ordersPage", "ordersCursor"}, allEntries = true)
    public com.project.order_service.model.Order receiveReturn(String trackingId, boolean restock,
            java.util.List<com.project.common.dto.request.OrderItemQtyRequest> items) {
        return orderDataService.receiveReturn(trackingId, restock, items);
    }
}
