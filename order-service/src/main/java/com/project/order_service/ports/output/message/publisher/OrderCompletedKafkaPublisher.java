package com.project.order_service.ports.output.message.publisher;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.order_service.model.Order;

import lombok.extern.slf4j.Slf4j;

/**
 * 구매 확정을 {@code order-completed} 토픽으로 알린다 (docs/25 §12).
 *
 * <p>듣는 쪽은 notification-service 하나다 — "구매가 확정되었습니다" 알림. 특히 <b>자동
 * 확정</b>은 손님이 아무것도 누르지 않았는데 주문 상태가 바뀌는 일이라, 알리지 않으면
 * 나중에 주문 내역을 열고서야 안다.
 *
 * <p>DB 트랜잭션 안에서 부르지 않는다. 호출자({@code OrderPurchaseConfirmationService})가
 * 커밋 뒤에 부른다 — 커밋이 실패했는데 알림만 나가면 "확정됐다" 는 거짓말이 된다.
 *
 * <p>{@code eventId} 는 {@code order-completed:<orderId>} 다. 같은 주문의 확정은 한 번뿐이므로
 * 재전달돼도 알림은 하나다(notification 의 sourceEventId 가 UNIQUE).
 */
@Slf4j
@Component
public class OrderCompletedKafkaPublisher {

    public static final String TOPIC = "order-completed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderCompletedKafkaPublisher(
            @Qualifier("orderCompletedKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(Order order) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("orderId", order.getOrderId());
        event.put("customerId", order.getCustomerId());
        event.put("status", "COMPLETED");
        event.put("auto", order.isAutoCompleted());
        LocalDateTime at = order.getCompletedAt();
        event.put("completedAt", at == null ? null : at.toString());
        event.put("eventId", TOPIC + ":" + order.getOrderId());

        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, String.valueOf(order.getOrderId()), json)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("구매 확정 이벤트 발행: orderId={} auto={}", order.getOrderId(), order.isAutoCompleted());
                        } else {
                            // 알림이 하나 빠질 뿐 주문 상태는 이미 확정됐다. 여기서 던지면 안 된다.
                            log.error("구매 확정 이벤트 발행 실패: orderId={}", order.getOrderId(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("구매 확정 이벤트를 만들지 못함: orderId={}", order.getOrderId(), e);
        }
    }
}
