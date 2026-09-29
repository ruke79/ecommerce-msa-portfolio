package com.project.delivery_service.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 상태 변경을 알린다.
 *
 * <p>두 곳이 듣는다:
 * <ul>
 *   <li><b>notification-service</b> — 알림·SMS. {@code OUT_FOR_DELIVERY} 와 {@code DELIVERED},
 *       그리고 통관 보류처럼 사용자가 조치해야 하는 상태가 특히 중요하다(docs/24).</li>
 *   <li><b>order-service</b> — 주문 상태를 {@code DISPATCHED}/{@code DELIVERED} 로 반영.
 *       그 값들은 enum 에만 있고 아무도 채우지 않던 것들이다(docs/25 §0).</li>
 * </ul>
 *
 * <p>발행 실패가 배송 처리를 막지 않는다. 상태는 이미 DB 에 있고, 다음 폴링에서 다시 전이가
 * 감지되지는 않으므로 알림 하나를 놓칠 수는 있다 — 그래도 배송 자체가 멈추는 것보다 낫다.
 * (아웃박스로 옮기는 것이 정석이지만, 그건 이 서비스에 outbox 테이블을 두는 별도 작업이다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStatusPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${delivery.topics.status:delivery-status}")
    private String topic;

    public void publish(Delivery delivery, DeliveryStatus status) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("deliveryId", delivery.getDeliveryId());
            event.put("orderId", delivery.getOrderId());
            event.put("customerId", delivery.getCustomerId());
            event.put("status", status.name());
            event.put("stage", status.stage().name());
            event.put("worthNotifying", status.isWorthNotifying());
            event.put("occurredAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).toString());
            // 알림 서비스가 이 값으로 중복을 거른다 — 같은 상태 전이가 재전달돼도 알림은 하나다.
            event.put("eventId", "delivery:" + delivery.getOrderId() + ":" + status.name());

            kafkaTemplate.send(topic, String.valueOf(delivery.getOrderId()),
                    objectMapper.writeValueAsString(event));
            log.debug("배송 상태 발행: orderId={} status={}", delivery.getOrderId(), status);

        } catch (Exception e) {
            log.error("배송 상태 발행 실패: orderId={} status={} — 알림이 나가지 않는다: {}",
                    delivery.getOrderId(), status, e.getMessage());
        }
    }
}
