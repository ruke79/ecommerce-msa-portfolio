package com.project.order_service.ports.output.message.publisher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스의 {@code delivery-resync} 행을 토픽으로 — delivery-service 가 그 주문의 배송 수량을 주문의 지금 값(배정된 수)으로 다시 맞춘다
 * (5단계 15, docs/53). 본문은 행에 적힌 JSON 그대로({@code orderId} · {@code eventId}), 키는 주문 id(같은 주문은 같은 파티션).
 *
 * <p>[JA] アウトボックスの delivery-resync 行をトピックへ — delivery-service がその注文の配送数量を注文の今の値(割り当てた数)に合わせ直す。
 */
@Slf4j
@Component
public class DeliveryResyncKafkaPublisher {

    public static final String TOPIC = "delivery-resync";

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs = 5000;

    // 문자열 JSON 프로듀서(트랜잭션 아님) — order-refunded 와 같은 설정을 쓴다. [JA] 文字列 JSON のプロデューサー — order-refunded と同じ設定。
    public DeliveryResyncKafkaPublisher(@Qualifier("orderRefundedKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** @return 상태가 확정됐으면 true, 일시 실패라 다음 폴링에서 다시 시도해야 하면 false */
    // [JA] 状態が確定したら true、一時失敗で次のポーリングで再試行すべきなら false
    public boolean publish(PaymentOutboxEvent outboxEvent, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {
        String orderId = outboxEvent.getAggregateId();
        try {
            SendResult<String, String> result = kafkaTemplate.send(TOPIC, orderId, outboxEvent.getPayload())
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("delivery-resync 발행: orderId={} offset={}", orderId, result.getRecordMetadata().offset());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | KafkaException | org.apache.kafka.common.KafkaException e) {
            log.error("delivery-resync 발행 실패 — 다음 폴링에서 다시 보낸다. orderId={} ({})", orderId, e.getMessage());
            return false;
        }
    }
}
