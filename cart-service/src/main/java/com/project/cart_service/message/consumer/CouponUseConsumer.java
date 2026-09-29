package com.project.cart_service.message.consumer;


import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.project.common.message.dto.request.CouponUseRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponUseConsumer {

    private static int seq = 0;
    
    private final CouponUseMessageListener userMessageListener;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;
    
      @KafkaListener(id = "coupon", topics = "coupon-use") 
    public void received(List<CouponUseRequest> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {    

      //  messages.forEach(userCreated ->
      //    log.info("User Id : {}", userCreated));

        // 실패 인덱스 보고 → 성공분 커밋 + 실패 레코드만 DLT 격리 (KafkaDltConfig, docs/11 §3)
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive coupon-use")) {
                userMessageListener.couponUsed(messages.get(i));
            } catch (Exception e) {
                throw new org.springframework.kafka.listener.BatchListenerFailedException(
                        "coupon-use 처리 실패", e, i);
            }
        }
    }
}
