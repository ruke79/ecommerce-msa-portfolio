package com.project.pay_service.ports.output.message.publisher;



import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.checkerframework.checker.units.qual.K;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.exception.PaymentDomainException;
import com.project.pay_service.outbox.model.OrderOutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseKafkaPublisher {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, PaymentResponse> kafkaTemplate;

    // 발행을 트랜잭션 안에서 기다리므로 이 값이 곧 행 잠금 보유 시간의 상한이다.
    @Value("${pay-service.outbox-publish-timeout-ms:5000}")
    private long publishTimeoutMs;

    /**
     * @return 상태가 확정되었으면 true, 일시적 실패라 다음 폴링에서 재시도해야 하면 false.
     *
     * <p>동기 발행이다. 비동기 콜백은 스케줄러 트랜잭션이 <b>커밋된 뒤</b> 프로듀서 IO 스레드에서
     * 실행되므로, 행 잠금이 상태 갱신 전에 풀린다 — 다른 레플리카가 같은 결제 응답을 STARTED 로
     * 보고 다시 발행한다. 발행을 트랜잭션 안에서 기다리면 그 창이 사라진다.
     * (order-service 의 PaymentRequestKafkaPublisher 에 같은 설명이 더 자세히 있다.)
     */
    public boolean publish(OrderOutboxEvent outboxEvent,  BiConsumer<OrderOutboxEvent, OutboxStatus> outboxCallback) {

        PaymentResponse payload = this.getOrderEventPayload(outboxEvent.getPayload(), PaymentResponse.class);

        Message<PaymentResponse> kafkaMessage = MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, "payment")
                // 키 = 주문 id — 같은 주문의 이벤트는 같은 파티션으로(docs/38 §1.15).
                .setHeader(KafkaHeaders.KEY, String.valueOf(payload.getOrderId()))
                // 🔒 Authorization 헤더는 싣지 않는다 — 예전 값은 스케줄러 스레드의 TokenContext 라 사실상 "Bearer null"
                //    이었고 받는 쪽은 "있는가" 만 봤다(docs/54 §1.7).
                .build();

        try {
            kafkaTemplate.send(kafkaMessage).get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("PaymentResponse for order id: {} published", payload.getOrderId());
            outboxCallback.accept(outboxEvent, OutboxStatus.COMPLETED);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing PaymentResponse for order id: {}", payload.getOrderId());
            return false;

        } catch (TimeoutException | ExecutionException | KafkaException e) {
            // 일시적 실패를 FAILED 로 적으면 STARTED 만 조회하는 스케줄러가 다시는 집지 않는다.
            log.error("Error while sending PaymentResponse to kafka with order id: {} — will retry next poll, error: {}",
                    payload.getOrderId(), e.getMessage());
            return false;
        }
    }

    public <T> T getOrderEventPayload(String payload, Class<T> outputType) {
        try {
            return objectMapper.readValue(payload, outputType);
        } catch (JsonProcessingException e) {
            log.error("Could not read {} object!", outputType.getName(), e);
            throw new PaymentDomainException("Could not read " + outputType.getName() + " object!", e);
        }
    }
}
