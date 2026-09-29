package com.project.notification_service.listener;

import java.util.List;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxTraceContext;
import com.project.notification_service.service.RestockSubscriptionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * catalog-service 의 {@code product-stock} — 옵션 재고가 0 을 건넜다 (docs/53).
 *
 * <p>{@code RESTOCKED}(0 → 있음)면 그 옵션에 재입고 알림을 신청한 손님에게 알린다. {@code SOLD_OUT} 은 여기서
 * 하는 일이 없다 — 품절은 운영자에게 알릴 일이고 catalog 의 로그 경보({@code ProductSoldOut}, docs/51 §4i)가
 * 맡는다. 손님은 상품 화면의 "품절" 로 충분하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestockEventListener {

    private final RestockSubscriptionService restockService;
    private final ObjectMapper objectMapper;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "notification-product-stock", topics = "product-stock",
                   containerFactory = "notificationListenerContainerFactory")
    public void onProductStock(List<String> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers Map<String, Object> kafkaHeaders) {
        for (int i = 0; i < messages.size(); i++) {
            try (OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive product-stock")) {
                JsonNode event = objectMapper.readTree(messages.get(i));
                if (!"RESTOCKED".equals(text(event, "type"))) {
                    continue;   // 품절은 운영자 경보가 맡는다
                }
                Long productId = number(event, "productId");
                String size = text(event, "size");
                if (productId == null || size == null || size.isBlank()) {
                    log.warn("재입고 이벤트에 상품·사이즈가 없어 알림을 만들지 못함: {}", abbreviate(messages.get(i)));
                    continue;
                }
                Long colorId = number(event, "colorId");
                int total = 0;
                int n;
                do {
                    // 페이지마다 트랜잭션이 따로다 — 서비스 프록시를 거쳐 부른다.
                    n = restockService.notifyPage(productId, colorId, size,
                            text(event, "name"), text(event, "slug"), text(event, "color"));
                    total += n;
                } while (n == RestockSubscriptionService.FANOUT_PAGE);
                if (total > 0) {
                    log.info("재입고 알림: productId={} colorId={} size={} 신청 {}건", productId, colorId, size, total);
                }
            } catch (Exception e) {
                throw new BatchListenerFailedException("product-stock 처리 실패", e, i);
            }
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long number(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String raw) {
        return raw == null ? "" : raw.substring(0, Math.min(raw.length(), 200));
    }
}
