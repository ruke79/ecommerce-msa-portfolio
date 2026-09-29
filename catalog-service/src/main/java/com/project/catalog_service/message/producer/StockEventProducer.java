package com.project.catalog_service.message.producer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 옵션(상품·색상·사이즈) 재고가 <b>0 을 건너는 순간</b>을 알린다 — 품절과 재입고 (docs/53).
 *
 * <ul>
 *   <li><b>품절</b>(있음 → 0): {@code 품절 발생} 을 warn 으로 남긴다. Loki 경보 {@code ProductSoldOut} 이 이
 *       문구로 운영자에게 알린다(docs/51 §4i) — 재고를 채울 사람은 운영자다. 손님은 상품 화면의 "품절" 로 충분하다.</li>
 *   <li><b>재입고</b>(0 → 있음): 환불·반품 입고로 재고가 돌아오거나 운영자가 채웠을 때. notification-service 가
 *       이 옵션에 재입고 알림을 신청한 손님에게 알린다.</li>
 * </ul>
 *
 * <p>둘 다 {@code product-stock} 토픽으로 나간다({@code type} 으로 갈린다). 0 을 건너지 않는 변화(5 → 3)는
 * 아무것도 하지 않는다 — 주문마다 이벤트가 나가면 소음이다.
 *
 * <p><b>커밋 뒤에</b> 보내고 찍는다. 재고 차감이 롤백됐는데 "품절" 경보가 나가거나 "재입고" 알림이 나가면
 * 거짓말이다. 커밋 전에 필요한 값은 미리 읽어 둔다(afterCommit 에는 영속성 컨텍스트가 닫혀 있을 수 있다).
 */
@Slf4j
@Component
public class StockEventProducer {

    /** 로그 경보가 찾는 문구 — observability/loki-rules 의 SOLD_OUT_MARKER 와 같아야 한다(B-61). */
    public static final String SOLD_OUT_MARKER = "품절 발생";

    public enum Transition { SOLD_OUT, RESTOCKED }

    /** 이벤트에 실을 옵션 정보. 커밋 전에 엔티티에서 읽어 둔 값이다. */
    public record Variant(Long productId, String slug, String name, Long colorId, String color, String size) {
    }

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${content.topics.product-stock:product-stock}")
    private String topic = "product-stock";

    public StockEventProducer(
            @Qualifier("contentEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** 0 을 건너는가. 건너지 않으면 null. */
    public static Transition transitionOf(int before, int after) {
        if (before > 0 && after <= 0) {
            return Transition.SOLD_OUT;
        }
        if (before <= 0 && after > 0) {
            return Transition.RESTOCKED;
        }
        return null;
    }

    public void onQuantityChanged(Variant variant, int before, int after) {
        Transition transition = transitionOf(before, after);
        if (transition == null || variant == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    announce(transition, variant, after);
                }
            });
            return;
        }
        announce(transition, variant, after);
    }

    private void announce(Transition transition, Variant v, int quantity) {
        if (transition == Transition.SOLD_OUT) {
            log.warn(SOLD_OUT_MARKER + ": productId={} colorId={} size={} name={}",
                    v.productId(), v.colorId(), v.size(), v.name());
        } else {
            log.info("재입고: productId={} colorId={} size={} quantity={}", v.productId(), v.colorId(), v.size(), quantity);
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", transition.name());
            event.put("productId", v.productId());
            event.put("slug", v.slug());
            event.put("name", v.name());
            event.put("colorId", v.colorId());
            event.put("color", v.color());
            event.put("size", v.size());
            event.put("quantity", quantity);
            event.put("occurredAt", LocalDateTime.now().toString());
            event.put("eventId", transition.name().toLowerCase() + ":" + v.productId() + ":" + v.colorId() + ":"
                    + v.size() + ":" + System.currentTimeMillis());
            // 같은 상품의 품절·재입고가 뒤집혀 도착하지 않게 상품 id 를 키로.
            kafkaTemplate.send(topic, String.valueOf(v.productId()), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // 재고는 이미 반영됐다. 알림만 못 나간 것이라 되돌리지 않는다.
            log.error("재고 이벤트 발행 실패: type={} productId={} — 재입고 알림이 나가지 않는다: {}",
                    transition, v.productId(), e.getMessage());
        }
    }
}
