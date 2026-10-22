package com.project.order_service.ports.output.message.publisher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.avro.OrderStatusChanged;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.service.OrderStatusChanger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스의 {@code order-status} 행 하나를 토픽 {@value #TOPIC} 으로 낸다(docs/76).
 *
 * <p>{@link PaymentRequestKafkaPublisher} 와 같은 규칙이다 — 동기 발행, 일시 실패는 상태를 건드리지 않아 다음 폴링이
 * 다시 집는다. 값은 Avro 단일 객체 인코딩의 {@link OrderStatusChanged}(DTO 없이 생성 레코드 그대로 —
 * {@code AvroSpecificSerde}, saga-projection 이 같은 serde 로 읽는다). 키는 주문 id.
 *
 * <p>[JA] アウトボックスの {@code order-status} 行をトピック order-status に出す(docs/76)。PaymentRequestKafkaPublisher と
 * 同じ規則 — 同期発行、一時的な失敗は状態を触らず次のポーリングが拾い直す。値は Avro 単一オブジェクト符号化の
 * OrderStatusChanged、キーは注文 id。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusKafkaPublisher {

    public static final String TOPIC = "order-status";

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, OrderStatusChanged> orderStatusKafkaTemplate;

    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    /** @return 상태가 확정됐으면 true, 일시 실패라 다음 폴링에서 다시 시도해야 하면 false */
    public boolean publish(PaymentOutboxEvent outboxEvent, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {
        OrderStatusChanged event = toEvent(outboxEvent);

        Message<OrderStatusChanged> message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, TOPIC)
                .setHeader(KafkaHeaders.KEY, String.valueOf(event.getOrderId()))
                .build();
        try {
            SendResult<String, OrderStatusChanged> result = orderStatusKafkaTemplate.send(message)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("OrderStatusChanged sent: orderId={} {} → {} offset={}", event.getOrderId(),
                    event.getPreviousStatus(), event.getStatus(), result.getRecordMetadata().offset());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | KafkaException | org.springframework.kafka.KafkaException e) {
            // 🐛 2026-10-22 실습 게임데이(kafka-pause): Kafka 가 꺼져 메타데이터 대기(max.block.ms)가 끝나면 KafkaTemplate 은 실패한 future 를
            //    돌려주지 않고 Spring 의 KafkaException("Send failed") 을 send() 에서 바로 던진다. 여기는 apache 의 KafkaException 만 잡아
            //    그 예외가 릴레이까지 올라가 '영구 오류' 로 FAILED — 주문 상태 이벤트 3건이 다시 보내지지 않았다(다른 발행기는 Spring 것을 잡는다).
            // [JA] Kafka 停止でメタデータ待ちが切れると KafkaTemplate は send() で Spring の KafkaException を直接投げる。ここは apache の
            //      KafkaException だけを捕まえていたのでリレーが恒久エラーとして FAILED にした — 注文状態イベントが再送されなかった。
            log.error("Failed to send OrderStatusChanged for order id: {} — will retry next poll. Error: {}",
                    event.getOrderId(), e.getMessage());
            return false;
        }
    }

    /** 행(JSON 페이로드 + 행 id) → Avro 레코드. 페이로드가 깨졌으면 재시도해도 같다 — 예외로 FAILED 가 되게 둔다. */
    public OrderStatusChanged toEvent(PaymentOutboxEvent outboxEvent) {
        OrderStatusChanger.Payload p;
        try {
            p = objectMapper.readValue(outboxEvent.getPayload(), OrderStatusChanger.Payload.class);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("order-status 아웃박스 페이로드를 읽지 못했다: id=" + outboxEvent.getId(), e);
        }
        return OrderStatusChanged.newBuilder()
                .setEventId(outboxEvent.getId())
                .setOrderId(p.orderId())
                .setCustomerId(p.customerId())
                .setPreviousStatus(p.previousStatus())
                .setStatus(p.status())
                .setSource(p.source())
                .setOccurredAt(p.occurredAt())
                .build();
    }
}
