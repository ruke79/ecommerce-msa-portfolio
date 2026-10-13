package com.project.order_service.outbox;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.request.TokenRequest;
import com.project.common.outbox.OutboxStatus;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Transactional
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOutboxHelper {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    // 커밋되면 폴링을 기다리지 않고 바로 릴레이한다 — 결제 사가의 첫 번째 대기(최대 10초)를 없앤다(docs/56).
    private final com.project.common.outbox.OutboxRelayTrigger relayTrigger;

    
    public void save(PaymentOutboxEvent outboxEvent) {
       // 새 행일 때만 추적 문맥을 남긴다. 같은 save() 가 릴레이의 상태 갱신에도 쓰이는데, 그때 남기면
       // 스케줄러의 문맥으로 덮어쓴다(docs/51 §4e).
       if (outboxEvent.isNew() && outboxEvent.getTraceContext() == null) {
           outboxEvent.setTraceContext(outboxTraceContext.capture());
       }
       PaymentOutboxEvent response = paymentOutboxRepository.save(outboxEvent);
       if (response == null) {
           log.error("Could not save OutboxEvent with outbox id: {}", outboxEvent.getId());
           throw new OrderDomainException("Could not save OutboxEvent with outbox id: " +
           outboxEvent.getId());
       }
       log.info("OutboxEvent saved with outbox id: {}", outboxEvent.getId());
    }

    @Transactional(readOnly = true)
    public Optional<List<PaymentOutboxEvent>> getPaymentOutboxEventByOutboxStatus(OutboxStatus status) {

        return paymentOutboxRepository.findByOutboxStatus(status);        
    }

    /**
     * 발행 대기 이벤트를 <b>선점</b>해 온다 ({@code FOR UPDATE SKIP LOCKED}).
     *
     * <p>읽기 전용이 아니다 — 행을 잠그고, 호출한 트랜잭션이 끝날 때까지 잡고 있어야 한다.
     * 그래야 다른 레플리카가 같은 행을 집어 중복 발행하는 일이 없다.
     */
    public List<PaymentOutboxEvent> claimPaymentOutboxEvents(OutboxStatus status, Pageable pageable) {

        return paymentOutboxRepository.claimByOutboxStatus(status, pageable);
    }



    
    public void savePaymentOutbox(PaymentRequest request, OutboxStatus status)  {

        String payload = this.createPayload(request);

        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(Long.toString(request.getOrderId()), "Order", "payment-request", payload, status);

        this.save(outboxEvent);
        relayOnCommit(status);
    }

    /**
     * 발행 대기 행을 썼으면 커밋 뒤 릴레이를 예약한다. 상태 갱신용 {@link #save} 에서 부르지 않는 것은
     * 의도다 — 릴레이 자신이 거기서 다음 릴레이를 부르게 된다.
     */
    private void relayOnCommit(OutboxStatus status) {
        if (status == OutboxStatus.STARTED) {
            relayTrigger.afterCommit();
        }
    }


      private String createPayload(PaymentRequest payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Could not create PayRequest object for order id: {}",
            payload.getOrderId(), e);
            throw new OrderDomainException("Could not create PayRequest object for order id: " +
            payload.getOrderId(), e);
        }
    }



    /** 아웃박스 이벤트 타입 — 릴레이가 이 이름으로 분기하고, Debezium EventRouter 는 이 이름을 토픽으로 쓴다(docs/76). */
    public static final String ORDER_STATUS_EVENT = "order-status";

    /**
     * 주문 상태 변경(docs/76) — {@link com.project.order_service.service.OrderStatusChanger} 만 부른다. 행 id 가 곧
     * 이벤트 id 다(릴레이가 Avro 의 eventId 에 넣는다 → 읽는 쪽이 재발행을 거른다).
     * [JA] 注文状態変更(docs/76) — OrderStatusChanger だけが呼ぶ。行 id がイベント id になる。
     */
    public void saveOrderStatusOutbox(com.project.order_service.service.OrderStatusChanger.Payload payload, OutboxStatus status) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OrderDomainException("주문 상태 이벤트를 직렬화하지 못했다: orderId=" + payload.orderId(), e);
        }
        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(Long.toString(payload.orderId()), "Order",
                ORDER_STATUS_EVENT, json, status);
        this.save(outboxEvent);
        relayOnCommit(status);
    }

     /**
     * 토큰 관련 정보를 Outbox에 저장하는 메서드 추가
     * TokenRequest는 토큰 정보(예: userId, tokenValue, tokenType 등)를 담는 DTO
     * @throws JacksonException 
     */
    public void saveTokenOutbox(TokenRequest request, OutboxStatus status) throws JacksonException {
        String payload = objectMapper.writeValueAsString(request);

        // 새로운 OutboxEvent 생성: aggregateType과 eventType을 토큰 관련으로 정의
        PaymentOutboxEvent outboxEvent = new PaymentOutboxEvent(
            Long.toString(request.getUserId()),  // Aggregate ID: 사용자 ID
            "UserToken",                         // Aggregate Type: 토큰 소유자
            "token-update",                      // Event Type: 토큰 업데이트
            payload,
            status
        );
        this.save(outboxEvent);
        relayOnCommit(status);
    }
    
    

}
