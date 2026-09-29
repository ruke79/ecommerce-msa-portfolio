package com.project.order_service.ports.input.message.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.service.OrderDeliveryStatusService;

/** delivery-service 의 DeliveryStatusPublisher 가 보내는 JSON 그대로 읽는다. */
@DisplayName("order-service — delivery-status 리스너")
class DeliveryStatusKafkaListenerTest {

    private static final String EVENT =
            "{\"deliveryId\":3,\"orderId\":889196209423729897,\"customerId\":7,\"status\":\"OUT_FOR_DELIVERY\","
            + "\"stage\":\"IN_TRANSIT\",\"worthNotifying\":true,\"occurredAt\":\"2026-09-19T01:02:03\","
            + "\"eventId\":\"delivery:889196209423729897:OUT_FOR_DELIVERY\"}";

    @Test
    @DisplayName("orderId·status·stage 를 꺼내 서비스에 넘긴다 — 큰 TSID 도 정확히")
    void passesFieldsThrough() {
        OrderDeliveryStatusService service = mock(OrderDeliveryStatusService.class);
        new DeliveryStatusKafkaListener(service, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP).received(List.of(EVENT), null);

        verify(service).apply(889196209423729897L, "OUT_FOR_DELIVERY", "IN_TRANSIT");
    }

    @Test
    @DisplayName("깨진 레코드는 그 인덱스만 실패로 표시하고 앞의 것은 처리된다")
    void brokenRecordIsIsolated() {
        OrderDeliveryStatusService service = mock(OrderDeliveryStatusService.class);
        DeliveryStatusKafkaListener listener = new DeliveryStatusKafkaListener(service, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

        assertThatThrownBy(() -> listener.received(List.of(EVENT, "not json"), null))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(e -> assertThat(((BatchListenerFailedException) e).getIndex()).isEqualTo(1));
        verify(service).apply(anyLong(), eq("OUT_FOR_DELIVERY"), anyString());
    }

    @Test
    @DisplayName("orderId 가 없는 이벤트는 경고만 남기고 넘어간다")
    void missingOrderIdIsSkipped() {
        OrderDeliveryStatusService service = mock(OrderDeliveryStatusService.class);
        new DeliveryStatusKafkaListener(service, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP)
                .received(List.of("{\"status\":\"DELIVERED\",\"stage\":\"DELIVERED\"}"), null);

        verify(service, never()).apply(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("낙관적 락 충돌은 재시도하지 않는다 — 다음 이벤트가 다시 판정한다")
    void optimisticLockIsSwallowed() {
        OrderDeliveryStatusService service = mock(OrderDeliveryStatusService.class);
        when(service.apply(anyLong(), anyString(), anyString()))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("busy"));

        new DeliveryStatusKafkaListener(service, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP).received(List.of(EVENT), null);
    }
}
