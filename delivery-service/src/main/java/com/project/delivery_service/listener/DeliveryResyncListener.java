package com.project.delivery_service.listener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.dto.order.OrderShippingDto;
import com.project.delivery_service.client.OrderServiceClient;
import com.project.delivery_service.service.DeliveryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code delivery-resync} — order 가 재고 부족을 적은 같은 트랜잭션에서 아웃박스로 보낸다(5단계 15, docs/53). 본문은 주문 id 뿐 — 보낼 수는
 * <b>지금</b> order 에 묻는다(배정된 수, 기존 내부 경로 — 재전달 · 순서가 바뀌어도 마지막 값으로 맞는다). 실패는 재시도 · DLT(공통 정책).
 *
 * <p>[JA] order が在庫不足を記録した同じトランザクションからアウトボックスで送る。本文は注文 id だけ — 送る数は今の order に問う。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryResyncListener {

    private final OrderServiceClient orderServiceClient;
    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "delivery-resync", topics = "delivery-resync", containerFactory = "deliveryListenerContainerFactory")
    public void onResync(List<String> messages,
            // 레코드마다 보낸 쪽(order 의 아웃박스 릴레이)의 추적을 이어받는다(docs/51 §4h). [JA] レコードごとに送り手のトレースを引き継ぐ。
            @org.springframework.messaging.handler.annotation.Headers Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive delivery-resync")) {
                handle(messages.get(i));
            } catch (Exception e) {
                throw new BatchListenerFailedException("배송 수량 다시 맞추기 실패", e, i);
            }
        }
    }

    void handle(String raw) throws Exception {
        JsonNode event = objectMapper.readTree(raw);
        JsonNode id = event.get("orderId");
        if (id == null || !id.canConvertToLong()) {
            log.warn("delivery-resync 에 orderId 가 없다 — 건너뜀");
            return;
        }
        long orderId = id.asLong();
        OrderShippingDto order = orderServiceClient.shipping(orderId);
        if (order == null || order.items() == null) {
            throw new IllegalStateException("주문 " + orderId + " 의 배송 품목을 읽지 못했다");
        }
        Map<Long, Integer> shippable = new LinkedHashMap<>();
        for (OrderShippingDto.Item item : order.items()) {
            if (item.orderProductId() != null) {
                shippable.put(item.orderProductId(), Math.max(0, item.quantity()));
            }
        }
        deliveryService.syncShippable(orderId, shippable);
    }
}
