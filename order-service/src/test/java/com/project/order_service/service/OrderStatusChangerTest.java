package com.project.order_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.avro.OrderStatusChanged;
import com.project.common.constants.OrderStatus;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.model.Order;
import com.project.order_service.outbox.PaymentOutboxHelper;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;
import com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher;

/**
 * 주문 상태 변경 → 아웃박스 이벤트(docs/76). 상태를 바꾸는 유일한 자리이므로 여기서 이벤트의 모양을 못 박는다.
 * [JA] 注文状態変更 → アウトボックスイベント(docs/76)。
 */
@DisplayName("order-service — 상태 변경은 이벤트가 된다 (docs/76)")
class OrderStatusChangerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T01:02:03Z");

    private final List<OrderStatusChanger.Payload> recorded = new ArrayList<>();
    private final OrderStatusChanger changer = new OrderStatusChanger(recorded::add, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Order order(OrderStatus status) {
        return Order.builder().orderId(42L).customerId(7L).orderStatus(status).build();
    }

    @Test
    @DisplayName("🔴 상태를 바꾸면 이전 · 새 상태 · 출처 · 시각이 담긴 이벤트가 하나 남는다")
    void changeRecordsOneEvent() {
        Order order = order(OrderStatus.PAID);

        assertThat(changer.change(order, OrderStatus.PROCESSING, "delivery")).isTrue();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(recorded).containsExactly(
                new OrderStatusChanger.Payload(42L, 7L, "PAID", "PROCESSING", "delivery", NOW));
    }

    @Test
    @DisplayName("같은 상태로 다시 바꾸는 것은 이벤트가 아니다 — 재전달 · 멱등 경로에서 타임라인이 늘지 않는다")
    void sameStatusIsNotAnEvent() {
        Order order = order(OrderStatus.PAID);

        assertThat(changer.change(order, OrderStatus.PAID, "payment-saga")).isFalse();
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("결제 확인은 상태 PAID 와 결제 시각을 함께 — 이미 PAID 면(재전달) 시각도 이벤트도 그대로")
    void markPaidKeepsFirstTimeAndEmitsOnce() {
        Order order = order(OrderStatus.NOT_PROCESSED);
        LocalDateTime first = LocalDateTime.of(2026, 10, 7, 10, 0);

        assertThat(changer.markPaid(order, first, "payment-saga")).isTrue();
        assertThat(changer.markPaid(order, first.plusMinutes(5), "payment-saga")).isFalse();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidCreatedAt()).isEqualTo(first);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).previousStatus()).isEqualTo("NOT_PROCESSED");
        assertThat(recorded.get(0).status()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("주문 생성은 이전 상태 없는 첫 항목이다")
    void createdHasNoPreviousStatus() {
        changer.created(order(OrderStatus.NOT_PROCESSED));

        assertThat(recorded).containsExactly(
                new OrderStatusChanger.Payload(42L, 7L, null, "NOT_PROCESSED", "created", NOW));
    }

    @Test
    @DisplayName("저장 전 주문(id 없음)에는 이벤트를 남길 수 없다 — 호출 순서의 버그를 조용히 넘기지 않는다")
    void requiresSavedOrder() {
        Order unsaved = Order.builder().customerId(7L).orderStatus(OrderStatus.NOT_PROCESSED).build();
        assertThatThrownBy(() -> changer.created(unsaved)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("기본값(withoutEvents)은 상태만 바꾼다 — 생성자를 쓰는 단위 테스트의 동작")
    void withoutEventsOnlyChangesStatus() {
        Order order = order(OrderStatus.PAID);
        assertThat(OrderStatusChanger.withoutEvents().change(order, OrderStatus.CANCELED, "x")).isTrue();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    // ---------------------------------------------------------------- 아웃박스 행 → Avro 이벤트

    @Test
    @DisplayName("🔴 아웃박스 행은 event_type=order-status · 키 = 주문 id 로 들어가고, 릴레이가 행 id 를 eventId 로 하는 Avro 로 바꾼다")
    void outboxRowBecomesAvroEventWithRowIdAsEventId() {
        PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        OutboxRelayTrigger trigger = mock(OutboxRelayTrigger.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PaymentOutboxHelper helper = new PaymentOutboxHelper(repository, mapper, OutboxTraceContext.NOOP, trigger);
        OrderStatusChanger real = new OrderStatusChanger(helper);

        Order order = order(OrderStatus.DELIVERED);
        real.change(order, OrderStatus.COMPLETED, "customer");

        ArgumentCaptor<PaymentOutboxEvent> row = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("order-status");
        assertThat(row.getValue().getAggregateType()).isEqualTo("Order");
        assertThat(row.getValue().getAggregateId()).isEqualTo("42");
        assertThat(row.getValue().getOutboxStatus()).isEqualTo(OutboxStatus.STARTED);
        verify(trigger).afterCommit();   // 커밋 뒤 바로 릴레이 — 폴링(최대 10초)을 기다리지 않는다

        OrderStatusChanged event = new OrderStatusKafkaPublisher(mapper, null).toEvent(row.getValue());
        assertThat(event.getEventId()).isEqualTo(row.getValue().getId());
        assertThat(event.getOrderId()).isEqualTo(42L);
        assertThat(event.getCustomerId()).isEqualTo(7L);
        assertThat(event.getPreviousStatus()).isEqualTo("DELIVERED");
        assertThat(event.getStatus()).isEqualTo("COMPLETED");
        assertThat(event.getSource()).isEqualTo("customer");
        assertThat(event.getOccurredAt()).isNotNull();
        // 개인정보 없음 — 이벤트 스키마에 그런 필드가 아예 없다
        assertThat(OrderStatusChanged.getClassSchema().getFields()).extracting(f -> f.name())
                .doesNotContain("email", "address", "token", "authorizationToken");
    }

    @Test
    @DisplayName("같은 상태면 아웃박스 행도 없다")
    void noRowWhenUnchanged() {
        PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class);
        PaymentOutboxHelper helper = new PaymentOutboxHelper(repository, new ObjectMapper().findAndRegisterModules(),
                OutboxTraceContext.NOOP, OutboxRelayTrigger.DISABLED);

        new OrderStatusChanger(helper).change(order(OrderStatus.PAID), OrderStatus.PAID, "payment-saga");

        verify(repository, never()).save(any());
    }
}
