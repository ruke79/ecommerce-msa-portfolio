package com.project.catalog_service.message.producer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.model.Event;

import lombok.extern.slf4j.Slf4j;

/**
 * 기획전이 시작됐다는 사실을 알린다.
 *
 * <p>공지 게시({@link NoticePublishedProducer})와 같은 모양이고, 알림 서비스도 같은
 * 구조로 받는다 — 받는 사람이 없는 <b>전체 알림</b>이다. 기획전은 모든 사용자에게 같은
 * 내용이므로 사용자 수만큼 알림 행을 만들 이유가 없다.
 *
 * <p><b>발행 실패가 시작을 되돌리지 않는다.</b> 기획전은 이미 ACTIVE 이고 할인이 걸렸다.
 * 알림을 못 보냈다고 세일을 취소하면 훨씬 나쁘다 — 가격이 내려간 것을 본 고객이 있다.
 */
@Slf4j
@Component
public class EventStartedProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${content.topics.event-started:event-started}")
    private String topic;

    public EventStartedProducer(
            @Qualifier("contentEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(Event event) {
        if (event == null || event.getEventId() == null) {
            return;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventKey", event.getEventId());
            payload.put("title", event.getTitle());
            payload.put("slug", event.getSlug());
            payload.put("description", event.getDescription());
            payload.put("productCount", event.getProducts().size());
            // 언제까지 하는지가 이 알림의 핵심이다 — 없으면 받아 두고 잊는다.
            payload.put("endsAt", event.getEndsAt() == null ? null : event.getEndsAt().toString());
            payload.put("occurredAt", LocalDateTime.now().toString());
            // 기획전 하나당 알림 하나. 스케줄러가 두 인스턴스에서 동시에 돌아도 이 키가
            // 같아서 알림은 하나다(source_event_id 가 UNIQUE).
            payload.put("eventId", "event:" + event.getEventId());

            kafkaTemplate.send(topic, String.valueOf(event.getEventId()),
                    objectMapper.writeValueAsString(payload));

            log.info("기획전 시작 발행: id={}, slug={}", event.getEventId(), event.getSlug());

        } catch (Exception e) {
            // 기획전은 이미 시작됐다. 알림만 못 나간 것이라 되돌리지 않는다.
            log.error("기획전 시작 발행 실패: id={} — 알림이 나가지 않는다: {}",
                    event.getEventId(), e.getMessage());
        }
    }
}
