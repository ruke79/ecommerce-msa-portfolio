package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.dto.request.OrderItemQtyRequest;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.repository.jpa.OrderRepository;
import com.project.order_service.service.OrderStockShortageService.Line;
import com.project.order_service.service.OrderStockShortageService.Outcome;

/**
 * 재고 부족 보상 — 적고, 자동 환불하고, 못 하면 넘긴다 (docs/53 §초과 판매).
 *
 * <p>[JA] 在庫不足の補償 — 記録し、自動返金し、できなければ引き継ぐ(docs/53 §過剰販売)。
 */
@DisplayName("재고 부족 보상")
class OrderStockShortageServiceTest {

    private static final String EVENT = "shortage-order-1";

    private OrderRepository orderRepository;
    private OrderService orderService;
    private OrderStockShortageService service;
    private Order order;
    private OrderProduct tee;
    private OrderProduct cap;
    private com.project.order_service.outbox.PaymentOutboxHelper outbox;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderService = mock(OrderService.class);
        outbox = mock(com.project.order_service.outbox.PaymentOutboxHelper.class);
        service = new OrderStockShortageService(
                new OrderStockShortageService.Recorder(orderRepository, mock(OrderCacheEvictor.class), outbox), orderService);

        order = Order.builder().orderId(1L).trackingId("track-1").customerId(7L)
                .total(new BigDecimal("50")).orderStatus(OrderStatus.PAID)
                .paymentProvider(PaymentProvider.STRIPE).providerPaymentId("cs_1").build();
        tee = OrderProduct.builder().orderProductId(101L).productId(10L).colorId(5L).size("M").qty(3)
                .price(new BigDecimal("10")).order(order).build();
        cap = OrderProduct.builder().orderProductId(102L).productId(11L).colorId(6L).size("F").qty(2)
                .price(new BigDecimal("10")).order(order).build();
        order.setOrderedProducts(new ArrayList<>(List.of(tee, cap)));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<OrderItemQtyRequest> refundedItems() {
        ArgumentCaptor<List<OrderItemQtyRequest>> items = ArgumentCaptor.forClass(List.class);
        verify(orderService).refundOrder(eq("track-1"), isNull(), eq(true), anyString(), items.capture());
        return items.getValue();
    }

    /** 🔴 핵심 — 3개 중 2개가 모자랐다. 2개를 적고 2개만큼 자동 환불한다. 모자라지 않은 품목은 건드리지 않는다. */
    // [JA] 🔴 核心 — 3 個のうち 2 個が足りなかった。2 個を記録し、2 個分だけ自動返金する。足りている品目には触れない。
    @Test
    @DisplayName("🔴 모자란 수를 품목에 적고 그만큼 자동 환불한다 — 관리자 권한의 기존 환불 경로로")
    void recordsAndRefunds() {
        Outcome outcome = service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));

        assertThat(outcome).isEqualTo(Outcome.REFUNDED);
        assertThat(tee.getShortQty()).isEqualTo(2);
        assertThat(cap.getShortQty()).isZero();
        assertThat(tee.allocatedQty()).as("보낼 수").isEqualTo(1);
        List<OrderItemQtyRequest> items = refundedItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getOrderProductId()).isEqualTo(101L);
        assertThat(items.get(0).getQty()).isEqualTo(2);
        assertThat(order.getShortageEventId()).isEqualTo(EVENT);
    }

    /** 5단계 15 — 배송이 부족 기록보다 먼저 만들어졌으면 주문 수량 그대로다. 적는 같은 트랜잭션에서 delivery 에 다시 맞추라고 알린다. */
    // [JA] 配送が不足記録より先に作られていれば注文数量のまま。記録する同じトランザクションで delivery に再調整を知らせる。
    @Test
    @DisplayName("🔴 부족을 적으면 같은 트랜잭션에 delivery-resync 아웃박스 행(키 주문 id) — 무통장(자동 환불 없음)에도, 재전달에는 다시 쓰지 않는다")
    void recordingWritesADeliveryResyncOutboxRow() {
        order.setPaymentProvider(PaymentProvider.BANK_TRANSFER);
        service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));
        verify(outbox).saveEventOutbox(eq(com.project.order_service.outbox.PaymentOutboxHelper.DELIVERY_RESYNC_EVENT), eq("1"),
                eq(java.util.Map.of("orderId", 1L, "eventId", "delivery-resync:" + EVENT)));

        service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));
        verify(outbox, times(1)).saveEventOutbox(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("같은 알림이 다시 오면(해결된 뒤) 적지도 환불하지도 않는다")
    void aResolvedDuplicateIsIgnored() {
        service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));
        order.setShortageResolvedAt(java.time.LocalDateTime.now());
        tee.setRefundedQty(2);   // 첫 환불이 반영된 상태 ／ [JA] 最初の返金が反映された状態

        Outcome again = service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));

        assertThat(again).isEqualTo(Outcome.DUPLICATE);
        assertThat(tee.getShortQty()).as("두 번 적지 않는다").isEqualTo(2);
        verify(orderService, times(1)).refundOrder(anyString(), any(), anyBoolean(), anyString(), any());
    }

    /**
     * 환불이 실패하면 예외 — Kafka 가 다시 전달한다. 그때는 적는 단계를 건너뛰고 환불만 다시 한다(두 번 적지 않는다).
     *
     * <p>[JA] 返金が失敗したら例外 — Kafka が再配信する。そのときは記録段階を飛ばして返金だけをやり直す(二度記録しない)。
     */
    @Test
    @DisplayName("🔴 환불이 실패하면 던지고, 다시 전달되면 적지 않고 환불만 다시 한다")
    void aFailedRefundIsRetriedWithoutRecordingTwice() {
        when(orderService.refundOrder(anyString(), any(), anyBoolean(), anyString(), any()))
                .thenThrow(new RuntimeException("provider down"));

        assertThatThrownBy(() -> service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2))))
                .hasMessageContaining("provider down");
        assertThat(order.getShortageResolvedAt()).isNull();

        org.mockito.Mockito.reset(orderService);
        assertThat(service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)))).isEqualTo(Outcome.REFUNDED);
        assertThat(tee.getShortQty()).isEqualTo(2);
        assertThat(refundedItems().get(0).getQty()).isEqualTo(2);
    }

    /** 무통장입금은 게이트웨이가 언제나 "받아들였다" 고 답한다 — 자동으로 부르면 돈은 안 갔는데 환불로 적힌다. */
    // [JA] 銀行振込はゲートウェイが常に「受け付けた」と答える — 自動で呼ぶとお金は送られていないのに返金として記録される。
    @Test
    @DisplayName("🔒 무통장입금 주문은 자동 환불하지 않고 운영자에게 넘긴다 — 부족은 적는다")
    void bankTransferGoesToAHuman() {
        order.setPaymentProvider(PaymentProvider.BANK_TRANSFER);

        assertThat(service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 1)))).isEqualTo(Outcome.MANUAL);

        verify(orderService, never()).refundOrder(anyString(), any(), anyBoolean(), anyString(), any());
        assertThat(tee.getShortQty()).isEqualTo(1);
        assertThat(order.getShortageResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 환불된 수는 다시 돌려주지 않는다 — 결제가 살아 있지 않으면 할 일이 없다")
    void nothingLeftToRefund() {
        order.setOrderStatus(OrderStatus.REFUNDED);

        assertThat(service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 1)))).isEqualTo(Outcome.NOTHING_TO_REFUND);
        verify(orderService, never()).refundOrder(anyString(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("모르는 주문은 운영자에게 넘긴다")
    void unknownOrder() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());

        assertThat(service.apply(9L, "shortage-order-9", List.of(new Line(10L, 5L, "M", 1))))
                .isEqualTo(Outcome.UNKNOWN_ORDER);
        verify(orderService, never()).refundOrder(anyString(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("주문한 수보다 많이 모자랄 수는 없다 — 배정된 수까지만 적는다")
    void shortNeverExceedsTheLine() {
        service.apply(1L, EVENT, List.of(new Line(11L, 6L, "F", 5)));

        assertThat(cap.getShortQty()).isEqualTo(2);
        assertThat(refundedItems().get(0).getQty()).isEqualTo(2);
    }

    @Test
    @DisplayName("배송에 넘기는 수는 배정된 수다 — 모자란 것은 창고에 없다")
    void shippingUsesAllocatedQty() {
        service.apply(1L, EVENT, List.of(new Line(10L, 5L, "M", 2)));

        var dto = OrderShippingInfoService.toDto(order);
        assertThat(dto.items()).extracting(i -> i.quantity()).containsExactly(1, 2);
    }
}
