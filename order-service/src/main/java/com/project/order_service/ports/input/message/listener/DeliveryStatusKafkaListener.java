package com.project.order_service.ports.input.message.listener;

import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.service.OrderDeliveryStatusService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code delivery-status} 토픽 → 주문 상태.
 *
 * <p>delivery-service 의 {@code DeliveryStatusPublisher} 는 처음부터 "order-service 가
 * DISPATCHED/DELIVERED 를 반영한다" 고 적어 두고 발행하고 있었는데, <b>듣는 쪽이 없었다</b>
 * (docs/25 §9 의 6단계). notification-service 가 같은 토픽을 알림용으로 듣고, 여기서는
 * 주문 상태용으로 듣는다 — 그룹이 다르니 서로 방해하지 않는다.
 *
 * <p>페이로드는 JSON 이다: {@code deliveryId, orderId, customerId, status, stage,
 * worthNotifying, occurredAt, eventId}. DTO 로 묶지 않고 필요한 셋만 꺼낸다 — 배송
 * 서비스가 필드를 더해도 여기가 죽지 않는다(notification-service 와 같은 판단).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStatusKafkaListener {

    private final OrderDeliveryStatusService deliveryStatusService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "order-delivery-status", topics = "delivery-status",
                   containerFactory = "deliveryStatusListenerContainerFactory")
    public void received(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            String raw = messages.get(i);
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive delivery-status")) {
                JsonNode event = objectMapper.readTree(raw);
                JsonNode orderIdNode = event.get("orderId");
                if (orderIdNode == null || orderIdNode.isNull()) {
                    log.warn("delivery-status 이벤트에 orderId 가 없음: {}", abbreviate(raw));
                    continue;
                }
                long orderId = orderIdNode.isNumber() ? orderIdNode.asLong() : Long.parseLong(orderIdNode.asText());
                deliveryStatusService.apply(orderId, text(event, "status"), text(event, "stage"));
            } catch (OptimisticLockingFailureException e) {
                // 다른 스레드가 같은 주문을 먼저 바꿨다. 다음 이벤트가 오면 다시 판정된다 — 재시도하지 않는다.
                log.warn("배송 상태 반영 중 낙관적 락 충돌, 건너뜀: {}", abbreviate(raw));
            } catch (Exception e) {
                // 이 레코드만 delivery-status.DLT 로, 앞의 성공분은 커밋된다(docs/11 §3).
                throw new BatchListenerFailedException("delivery-status 처리 실패", e, i);
            }
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String abbreviate(String raw) {
        return raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
    }
}
