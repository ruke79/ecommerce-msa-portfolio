package com.project.order_service.ports.output.message.publisher;


import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.message.dto.request.CartEmptyRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartEmptyKafkaPublisher {

    private final KafkaTemplate<String, CartEmptyRequest> kafkaTemplate;

    @Transactional
    public void publish(CartEmptyRequest message) {
     String topic = "cart-empty";

        try {
            Message<CartEmptyRequest> kafkaMessage = MessageBuilder.withPayload(message)
                    .setHeader(KafkaHeaders.TOPIC, topic)
                    // 키 = 사용자 id — 같은 장바구니의 비우기/롤백은 같은 파티션으로(docs/38 §1.15).
                    .setHeader(KafkaHeaders.KEY, String.valueOf(message.getUserId()))
                    .build();

            CompletableFuture<SendResult<String, CartEmptyRequest>> future = com.project.common.kafka.TransactionalSends.run(kafkaTemplate, ops -> ops.send(kafkaMessage));   // 트랜잭션 밖에서도 보낸다

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Successfully sent CartEmptyRequest to Kafka with Authorization header. Offset: {}",
                             result.getRecordMetadata().offset());
                } else {
                    log.error("Failed to send CartEmptyRequest to Kafka. Error: {}", ex.getMessage());
                }
            });

        } catch (KafkaException e) {
            log.error("Error while adding CartEmptyRequest to Kafka producer buffer: {}", e.getMessage());
        }
    }
}
