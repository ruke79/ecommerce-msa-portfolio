package com.project.order_service.ports.input.message.listener;

import java.util.ArrayList;
import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.service.OrderStockShortageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code stock-shortage} 토픽 → 재고 부족 보상(적기 + 자동 환불, docs/53 §초과 판매).
 *
 * <p>페이로드는 catalog 의 {@code StockShortageRecorder} 가 만든 JSON 이다: {@code eventId, orderId,
 * lines[{productId, colorId, size, orderedQty, shortQty}], occurredAt}. DTO 로 묶지 않고 필요한 것만 꺼낸다 —
 * {@code delivery-status} 와 같은 판단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockShortageKafkaListener {

    private final OrderStockShortageService shortageService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "order-stock-shortage", topics = "stock-shortage",
                   containerFactory = "stockShortageListenerContainerFactory")
    public void received(List<String> messages,
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive stock-shortage")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                long orderId = event.get("orderId").asLong();
                String eventId = event.get("eventId").asText();
                List<OrderStockShortageService.Line> lines = new ArrayList<>();
                for (JsonNode l : event.path("lines")) {
                    lines.add(new OrderStockShortageService.Line(
                            l.get("productId").isNull() ? null : l.get("productId").asLong(),
                            l.path("colorId").isNull() || l.path("colorId").isMissingNode() ? null : l.get("colorId").asLong(),
                            l.path("size").isNull() || l.path("size").isMissingNode() ? null : l.get("size").asText(),
                            l.path("shortQty").asInt()));
                }
                shortageService.apply(orderId, eventId, lines);
            } catch (Exception e) {
                // 이 레코드만 재시도 후 stock-shortage.DLT 로, 앞의 성공분은 커밋된다(docs/11 §3).
                throw new BatchListenerFailedException("stock-shortage 처리 실패", e, i);
            }
        }
    }
}
