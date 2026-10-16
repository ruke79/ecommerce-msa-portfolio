package com.project.order_service.ports.output.message.publisher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스의 {@code product-update} 행(결제 확정 차감 · 반품 입고 복원)을 토픽 {@value #TOPIC} 으로 낸다.
 *
 * <p>{@link OrderStatusKafkaPublisher} 와 같은 규칙 — 동기 발행, 일시 실패는 상태를 건드리지 않아 다음 폴링이 다시 집는다.
 * 키 = eventId({@code order-<id>} · {@code restock-<id>-u<n>}) — 같은 주문의 원본과 재전달이 같은 파티션으로 간다(docs/38 §1.15).
 * catalog 는 이 eventId 로 중복을 거른다.
 *
 * <p>🐛 예전에는 사가가 바로 보내고 결과는 로그뿐이었다 — 주문이 PAID 로 커밋된 뒤 발행이 빠지면 재고가 차감되지 않았다.
 *
 * <p>[JA] アウトボックスの product-update 行をトピックに出す。同期発行、一時的な失敗は次のポーリングが拾い直す。以前はサガが直接送り、
 * 結果はログだけで、PAID のコミット後に発行が抜けると在庫が減らなかった。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductUpdateKafkaPublisher {

    public static final String TOPIC = "product-update";

    private final KafkaTemplate<String, ProductUpdateRequest> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs = 5000;

    /** @return 상태가 확정됐으면 true, 일시 실패라 다음 폴링에서 다시 시도해야 하면 false */
    public boolean publish(PaymentOutboxEvent outboxEvent, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {
        ProductUpdateRequest request = read(outboxEvent);
        try {
            // 트랜잭션 프로듀서 — 릴레이의 DB 트랜잭션에는 Kafka 트랜잭션이 없으므로 이 한 번을 감싸 보낸다(TransactionalSends).
            SendResult<String, ProductUpdateRequest> result = com.project.common.kafka.TransactionalSends
                    .run(kafkaTemplate, ops -> ops.send(TOPIC, request.getEventId(), request))
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("product-update 발행: eventId={} restock={} lines={} offset={}", request.getEventId(), request.isRestock(),
                    request.getOrderProducts() == null ? 0 : request.getOrderProducts().size(), result.getRecordMetadata().offset());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | KafkaException | org.apache.kafka.common.KafkaException e) {
            log.error("product-update 발행 실패 — 다음 폴링에서 다시 보낸다. eventId={} ({})", request.getEventId(), e.getMessage());
            return false;
        }
    }

    /** 행 → 메시지. 페이로드가 깨졌으면 재시도해도 같다 — 예외로 FAILED 가 되게 둔다. */
    ProductUpdateRequest read(PaymentOutboxEvent outboxEvent) {
        try {
            return objectMapper.readValue(outboxEvent.getPayload(), ProductUpdateRequest.class);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("product-update 아웃박스 페이로드를 읽지 못했다: id=" + outboxEvent.getId(), e);
        }
    }
}
