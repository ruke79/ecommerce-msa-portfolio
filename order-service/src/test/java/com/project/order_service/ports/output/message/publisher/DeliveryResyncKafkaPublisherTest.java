package com.project.order_service.ports.output.message.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;

/** delivery-resync 행 발행 — 키 주문 id · 본문 그대로, 성공이면 COMPLETED, 실패면 다음 폴링(5단계 15). [JA] 成功で COMPLETED、失敗で次のポーリング。 */
@DisplayName("delivery-resync 발행")
class DeliveryResyncKafkaPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final DeliveryResyncKafkaPublisher publisher = new DeliveryResyncKafkaPublisher(template);
    private final PaymentOutboxEvent row = new PaymentOutboxEvent("42", "Order", "delivery-resync",
            "{\"orderId\":42,\"eventId\":\"delivery-resync:e\"}", OutboxStatus.STARTED);

    @Test
    @DisplayName("🔴 키 = 주문 id · 본문 그대로 · 보내면 COMPLETED")
    void publishesAndCompletes() {
        when(template.send("delivery-resync", "42", row.getPayload())).thenReturn(CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>("delivery-resync", "42", row.getPayload()),
                        new RecordMetadata(new TopicPartition("delivery-resync", 0), 0, 0, 0, 0, 0))));
        List<OutboxStatus> seen = new ArrayList<>();
        assertThat(publisher.publish(row, (r, s) -> seen.add(s))).isTrue();
        assertThat(seen).containsExactly(OutboxStatus.COMPLETED);
    }

    @Test
    @DisplayName("브로커가 실패하면 false — 행은 그대로, 다음 폴링에 다시")
    void failureLeavesTheRowForTheNextPoll() {
        when(template.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        List<OutboxStatus> seen = new ArrayList<>();
        assertThat(publisher.publish(row, (r, s) -> seen.add(s))).isFalse();
        assertThat(seen).isEmpty();
    }
}
