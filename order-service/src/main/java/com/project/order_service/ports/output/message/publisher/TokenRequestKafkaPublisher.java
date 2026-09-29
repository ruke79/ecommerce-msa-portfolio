package com.project.order_service.ports.output.message.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.TokenRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.ports.output.message.publisher.TokenRequestMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class TokenRequestKafkaPublisher implements TokenRequestMessagePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 🐛 이름으로 집어서 받는다. 예전에는 {@code @RequiredArgsConstructor} 가 타입만으로 주입했는데,
     * 구매 확정 알림(docs/25 §12)이 {@code KafkaTemplate<String, String>} 을 하나 더 만들자 후보가 둘이
     * 되어 <b>order-service 가 기동하지 못했다</b>(NoUniqueBeanDefinitionException). lombok 은
     * {@code @Qualifier} 를 생성자로 옮겨 주지 않으므로 생성자를 직접 쓴다.
     * {@code KafkaPublisherWiringTest} 가 실제 설정으로 배선을 확인한다.
     */
    public TokenRequestKafkaPublisher(
            @Qualifier("tokenUpdateKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${order-service.token-updates-topic-name:token-u}")
    private String tokenUpdatesTopicName;

    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    @Override
    public boolean publish(PaymentOutboxEvent outboxMessage, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {

        TokenRequest tokenRequest;
        try {
            tokenRequest = objectMapper.readValue(outboxMessage.getPayload(), TokenRequest.class);
        } catch (JsonProcessingException e) {
            // 페이로드가 깨진 것은 재시도해도 낫지 않는다 — 유일하게 FAILED 가 맞는 경우다.
            log.error("Could not read TokenRequest object for outbox id: {}", outboxMessage.getId(), e);
            outboxCallback.accept(outboxMessage, OutboxStatus.FAILED);
            return true;
        }

        String key = tokenRequest.getUserId().toString();
        String payload = outboxMessage.getPayload();

        log.info("Sending token update message to topic: {} with userId: {}", tokenUpdatesTopicName, key);

        // 동기 발행 — 스케줄러 트랜잭션이 행 잠금을 쥔 채로 결과를 확정한다.
        // (비동기 콜백은 트랜잭션이 커밋된 뒤 실행돼 다른 레플리카에 중복 발행 창을 열어 준다.)
        try {
            kafkaTemplate.send(tokenUpdatesTopicName, key, payload)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Token update message sent successfully for outbox id: {}", outboxMessage.getId());
            outboxCallback.accept(outboxMessage, OutboxStatus.COMPLETED);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing token update for outbox id: {}", outboxMessage.getId());
            return false;

        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            // 일시적 실패를 FAILED 로 적으면 STARTED 만 조회하는 스케줄러가 다시는 집지 않는다.
            log.error("Failed to send token update for outbox id: {} — will retry next poll",
                    outboxMessage.getId(), e);
            return false;
        }
    }
}