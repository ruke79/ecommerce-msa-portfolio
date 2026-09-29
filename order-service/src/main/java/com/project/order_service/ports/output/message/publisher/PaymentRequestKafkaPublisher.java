package com.project.order_service.ports.output.message.publisher;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.context.TokenContext;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.domain.exception.OrderDomainException;

import com.project.order_service.outbox.model.PaymentOutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestKafkaPublisher {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, PaymentRequest> kafkaTemplate;

    // 발행을 트랜잭션 안에서 기다리므로, 이 값이 곧 행 잠금을 쥐고 있는 최대 시간이다.
    // 너무 크면 브로커 장애 때 커넥션이 오래 묶이고, 너무 작으면 정상 지연에도 재시도가 늘어난다.
    @Value("${order-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    /**
     * 아웃박스 이벤트 하나를 Kafka 로 발행한다.
     *
     * @return 이 이벤트의 상태가 <b>확정</b>되었으면 true(COMPLETED 또는 재시도 불가로 FAILED),
     *         일시적 실패라 다음 폴링에서 다시 시도해야 하면 false.
     *
     * <p><b>동기 발행인 이유.</b> 예전에는 {@code whenComplete} 콜백에서 상태를 갱신했다. 그
     * 콜백은 Kafka 프로듀서 IO 스레드에서, <b>스케줄러 트랜잭션이 이미 커밋된 뒤</b> 실행된다.
     * 결과는 두 가지였다:
     * <ul>
     *   <li>행 잠금이 상태 갱신 <b>전에</b> 풀린다 → 다른 레플리카가 같은 행을 STARTED 로 보고
     *       다시 발행한다. SKIP LOCKED 만으로는 이 창을 막을 수 없다.</li>
     *   <li>{@code save()} 가 트랜잭션 밖에서 불린다 → 자기 트랜잭션을 열어 어찌어찌 되지만,
     *       조회와 갱신이 원자적이지 않다.</li>
     * </ul>
     * 발행을 트랜잭션 안에서 기다리면 잠금이 상태 확정까지 유지된다.
     *
     * <p><b>일시적 실패를 FAILED 로 적지 않는 이유.</b> 스케줄러는 STARTED 만 조회한다. 브로커가
     * 잠깐 흔들렸다고 FAILED 로 적으면 그 이벤트는 <b>영원히 재시도되지 않는다</b> — 결제 요청이
     * 조용히 사라진다. 상태를 그대로 두면 다음 폴링이 자연히 재시도한다.
     */
    public boolean publish(PaymentOutboxEvent outboxEvent, BiConsumer<PaymentOutboxEvent, OutboxStatus> outboxCallback) {

        PaymentRequest payload = this.getOrderEventPayload(outboxEvent.getPayload(), PaymentRequest.class);

        Message<PaymentRequest> kafkaMessage = MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, "order")
                // 키 = 주문 id. 키가 없으면 파티션이 둘 이상일 때 같은 주문의 이벤트가 서로 다른
                // 파티션으로 흩어져 순서가 깨진다. 지금은 토픽이 파티션 하나라 드러나지 않지만,
                // 파티션을 늘리는 순간 조용히 깨지는 종류다(docs/38 §1.15).
                .setHeader(KafkaHeaders.KEY, String.valueOf(payload.getOrderId()))
                // 🔒 Authorization 헤더는 싣지 않는다 — 예전에는 사용자 토큰 원문이 실렸고 받는 쪽은 "있는가" 만 봤다
                //    (docs/54 §1.7). 토픽에 쓸 수 있는 주체는 브로커 ACL 이 정한다.
                .build();

        try {
            SendResult<String, PaymentRequest> result = kafkaTemplate.send(kafkaMessage)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);

            log.info("PaymentRequest for order ID: {} sent successfully to topic: {} with offset: {}",
                    payload.getOrderId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().offset());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing PaymentRequest for order id: {}", payload.getOrderId());
            return false;

        } catch (TimeoutException | ExecutionException | KafkaException e) {
            // 일시적 실패로 본다 — 상태를 건드리지 않으면 다음 폴링이 다시 집는다.
            log.error("Failed to send PaymentRequest for order id: {} — will retry next poll. Error: {}",
                    payload.getOrderId(), e.getMessage());
            return false;
        }
    }

    public <T> T getOrderEventPayload(String payload, Class<T> outputType) {
        try {
            return objectMapper.readValue(payload, outputType);
        } catch (JsonProcessingException e) {
            log.error("Could not read {} object!", outputType.getName(), e);
            throw new OrderDomainException("Could not read " + outputType.getName() + " object!", e);
        }
    }
}
