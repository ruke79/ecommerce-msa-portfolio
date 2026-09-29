package com.project.catalog_service.message.producer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.model.StockShortage;
import com.project.catalog_service.repository.jpa.data.StockShortageRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 재고 부족 아웃박스를 {@code stock-shortage} 토픽으로 내보낸다 (docs/53 §초과 판매).
 *
 * <p>보내고 나서 표시한다. 순서를 바꾸면 발행이 실패했는데 "보냈다" 가 된다. 이 순서면 표시가 실패해도 다음 주기에
 * 다시 보내고, order 가 {@code eventId} 로 두 번째를 버린다 — 파드가 여럿이라 같은 행을 둘이 보내도 마찬가지다.
 *
 * <p>오래 못 보내면(브로커가 계속 거부) 운영자에게 넘긴다 — 그동안 손님은 환불을 기다리고 있다.
 */
@Slf4j
@Component
public class StockShortagePublisher {

    static final int BATCH = 50;
    static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    private final StockShortageRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${catalog.topics.stock-shortage:stock-shortage}")
    private String topic = "stock-shortage";

    public StockShortagePublisher(StockShortageRepository repository,
            @Qualifier("contentEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${catalog.stock-shortage.publish-interval-ms:2000}")
    @Transactional
    public void publishPending() {
        List<StockShortage> pending = repository.findUnpublished(PageRequest.of(0, BATCH));
        for (StockShortage s : pending) {
            try {
                // 주문 id 가 키 — 같은 주문의 기록이 순서대로 간다.
                kafkaTemplate.send(topic, String.valueOf(s.getOrderId()), s.getPayload()).get(5, TimeUnit.SECONDS);
                s.markPublished();
                repository.save(s);
            } catch (Exception e) {
                if (s.getCreatedAt().isBefore(LocalDateTime.now().minus(STUCK_AFTER))) {
                    log.error("재고 부족 기록을 {}분 넘게 보내지 못했다 — 수동 확인 필요. 손님이 환불을 기다린다. orderId={} eventId={}: {}",
                            STUCK_AFTER.toMinutes(), s.getOrderId(), s.getEventId(), e.toString());
                } else {
                    log.warn("재고 부족 기록 발행 실패 — 다음 주기에 다시 보낸다. orderId={}: {}", s.getOrderId(), e.toString());
                }
                return;   // 브로커 문제면 나머지도 실패한다 — 다음 주기에
            }
        }
    }
}
