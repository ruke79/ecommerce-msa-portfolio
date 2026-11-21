package com.project.delivery_service.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.service.DeliveryService;

/**
 * delivery-resync — 주문 id 만 받아 보낼 수를 order 에 <b>지금</b> 묻고 그것으로 맞춘다(5단계 15).
 * [JA] 注文 id だけを受け取り、送る数を今の order に問い、それで合わせる。
 */
@DisplayName("delivery-resync — 주문의 지금 보낼 수로")
class DeliveryResyncListenerTest {

    private final OrderServiceClient orders = mock(OrderServiceClient.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final DeliveryResyncListener listener = new DeliveryResyncListener(orders, deliveries, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    @Test
    @DisplayName("🔴 order 의 배정된 수(품목 id 마다)로 다시 맞춘다")
    void syncsToOrderShippableQuantities() {
        when(orders.shipping(42L)).thenReturn(new OrderShippingDto(42L, 7L, "PAID", null, List.of(
                new OrderShippingDto.Item(101L, 10L, "tee", null, 1, "T"), new OrderShippingDto.Item(102L, 11L, "cap", null, 0, "C"))));
        listener.onResync(List.of("{\"orderId\":42,\"eventId\":\"delivery-resync:x\"}"), null);
        verify(deliveries).syncShippable(42L, Map.of(101L, 1, 102L, 0));
    }

    @Test
    @DisplayName("order 에 닿지 못하면 실패(재시도 · DLT) · orderId 가 없으면 건너뜀")
    void failuresRetryAndBadRecordsSkip() {
        when(orders.shipping(anyLong())).thenThrow(new RuntimeException("down"));
        assertThatThrownBy(() -> listener.onResync(List.of("{\"orderId\":42}"), null)).isInstanceOf(BatchListenerFailedException.class);
        listener.onResync(List.of("{\"eventId\":\"x\"}"), null);
        verify(deliveries, never()).syncShippable(any(), any());
    }
}
