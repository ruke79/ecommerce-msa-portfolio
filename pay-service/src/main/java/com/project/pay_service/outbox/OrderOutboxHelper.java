package com.project.pay_service.outbox;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.exception.PaymentDomainException;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.outbox.repository.jpa.OrderOutboxRepository;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxHelper {

    private final OrderOutboxRepository orderOutboxRepository;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    // 커밋되면 폴링을 기다리지 않고 바로 릴레이한다 — 결제 사가의 두 번째 대기(최대 10초)를 없앤다(docs/56).
    private final com.project.common.outbox.OutboxRelayTrigger relayTrigger;
    //private final PaymentResponseKafkaPublisher paymentResponseMessagePublisher;

    @Transactional
    public void save(OrderOutboxEvent outboxEvent) {
       // 새 행일 때만 추적 문맥을 남긴다. 같은 save() 가 릴레이의 상태 갱신에도 쓰이는데, 그때 남기면
       // 스케줄러의 문맥으로 덮어쓴다(docs/51 §4e).
       if (outboxEvent.isNew() && outboxEvent.getTraceContext() == null) {
           outboxEvent.setTraceContext(outboxTraceContext.capture());
       }
       OrderOutboxEvent response = orderOutboxRepository.save(outboxEvent);
       if (response == null) {
           log.error("Could not save OutboxEvent with outbox id: {}", outboxEvent.getId());
           throw new PaymentDomainException("Could not save OutboxEvent with outbox id: " +
           outboxEvent.getId());
       }
       log.info("OutboxEvent saved with outbox id: {}", outboxEvent.getId());
    }

    @Transactional(readOnly = true)
    public Optional<List<OrderOutboxEvent>> getOrderOutboxEventByOutboxStatus(OutboxStatus status) {

        return orderOutboxRepository.findByOutboxStatus(status);        
    }

    /**
     * 발행 대기 이벤트를 <b>선점</b>해 온다 ({@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>읽기 전용이 아니다 — 행을 잠그고, 호출한 트랜잭션이 끝날 때까지 잡고 있어야 한다.
     * 그래야 다른 레플리카가 같은 행을 집어 중복 발행하는 일이 없다.
     */
    public List<OrderOutboxEvent> claimOrderOutboxEvents(OutboxStatus status, Pageable pageable) {

        return orderOutboxRepository.claimByOutboxStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<OrderOutboxEvent> getCompletedOrderOutboxEvent(String aggregateId, OutboxStatus status) {

        return orderOutboxRepository.findByAggregateIdAndOutboxStatus(aggregateId, status);
    }


    @Transactional
    public void saveOrderOutbox(PaymentResponse response, OutboxStatus outboxStatus)  {

        String payload = this.createPayload(response);

        // aggregate_id = 주문 id. 예전에는 paymentId 였는데 두 가지가 어긋나 있었다.
        //  1. PaymentRequestMessageListener 의 멱등성 검사가 orderId 로 findByAggregateId 를 하므로
        //     paymentId 가 들어 있으면 **한 번도 맞지 않았다** — 재전달을 걸러낸 적이 없다.
        //  2. Debezium 릴레이의 키가 이 컬럼이라, 스케줄러 릴레이(orderId 키)와 다른 파티션에 앉았다.
        // payment 토픽의 소비자(order 사가·coupon·delivery·notification)는 전부 주문 단위로 움직인다.
        OrderOutboxEvent outboxEvent = new OrderOutboxEvent(response.getOrderId().toString(),
        "Payment", "payment-response", payload, outboxStatus);

        this.save(outboxEvent);

        // 상태 갱신용 save() 가 아니라 여기서 부른다 — 릴레이 자신이 다음 릴레이를 부르지 않게.
        if (outboxStatus == OutboxStatus.STARTED) {
            relayTrigger.afterCommit();
        }
    }


      private String createPayload(PaymentResponse payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Could not create PayRequest object for order id: {}",
            payload.getOrderId(), e);
            throw new PaymentDomainException("Could not create PayRequest object for order id: " +
            payload.getOrderId(), e);
        }
    }

    public void updateOutboxStatus(OrderOutboxEvent outboxEvent, OutboxStatus outboxStatus) {
        outboxEvent.setOutboxStatus(outboxStatus);
        this.save(outboxEvent);
        log.info("OrderOutboxEvent is updated with outbox status: {}", outboxStatus.name());
    }

    
    


    /** 한 주문의 응답 이벤트 전부 — 결제 완료 응답과 환불 응답은 같은 주문 id 에 따로 쌓인다. */
    @Transactional(readOnly = true)
    public List<OrderOutboxEvent> getCompletedOrderOutboxEvents(String aggregateId, OutboxStatus status) {
        return orderOutboxRepository.findAllByAggregateIdAndOutboxStatus(aggregateId, status);
    }

    /**
     * 저장된 응답이 말하는 결제 상태. 페이로드(JSON)에서 읽는다 — event_type 은 스케줄러·CDC 의
     * 토픽 라우팅 키({@code payment-response} → {@code payment})라 상태를 실을 수 없다.
     *
     * @return 못 읽으면 null
     */
    public PaymentStatus paymentStatusOf(OrderOutboxEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload()).path("paymentStatus");
            return node.isMissingNode() || node.isNull() ? null : PaymentStatus.valueOf(node.asText());
        } catch (Exception e) {
            log.warn("아웃박스 페이로드에서 결제 상태를 읽지 못함: outboxId={}", event.getId());
            return null;
        }
    }
}
