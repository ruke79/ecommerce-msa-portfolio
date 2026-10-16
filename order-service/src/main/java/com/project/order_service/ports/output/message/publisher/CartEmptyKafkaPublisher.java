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
import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스의 {@code cart-empty} 행(결제 뒤 주문한 줄 빼기)을 토픽 {@value #TOPIC} 으로 낸다. 규칙은
 * {@link ProductUpdateKafkaPublisher} 와 같다. 키 = 사용자 id — 같은 장바구니의 비우기 · 롤백은 같은 파티션으로(docs/38 §1.15).
 *
 * <p>[JA] アウトボックスの cart-empty 行をトピックに出す。キーはユーザー id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartEmptyKafkaPublisher {

    public static final String TOPIC = "cart-empty";

    private final KafkaTemplate<String, CartEmptyRequest> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs = 5000;

    /** @return 상태가 확정됐으면 true, 일시 실패라 다음 폴링에서 다시 시도해야 하면 false */
    public boolean publish(PaymentOutboxEvent outboxEvent, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {
        CartEmptyRequest request = read(outboxEvent);
        try {
            SendResult<String, CartEmptyRequest> result = com.project.common.kafka.TransactionalSends
                    .run(kafkaTemplate, ops -> ops.send(TOPIC, String.valueOf(request.getUserId()), request))
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("cart-empty 발행: userId={} lines={} offset={}", request.getUserId(),
                    request.getItems() == null ? 0 : request.getItems().size(), result.getRecordMetadata().offset());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | KafkaException | org.apache.kafka.common.KafkaException e) {
            log.error("cart-empty 발행 실패 — 다음 폴링에서 다시 보낸다. userId={} ({})", request.getUserId(), e.getMessage());
            return false;
        }
    }

    CartEmptyRequest read(PaymentOutboxEvent outboxEvent) {
        try {
            return objectMapper.readValue(outboxEvent.getPayload(), CartEmptyRequest.class);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("cart-empty 아웃박스 페이로드를 읽지 못했다: id=" + outboxEvent.getId(), e);
        }
    }
}
