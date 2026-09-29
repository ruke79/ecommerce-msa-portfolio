package com.project.coupon_service.message.consumer;

import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestHeader;

import com.project.common.message.dto.request.CartEmptyRequest;
import com.project.common.message.dto.response.PaymentResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseConsumer {

    private final PaymentResponseMessageListener paymentResponseMessageListener;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id="coupon-used", topics = "payment", containerFactory = "paymentResponseContainerFactory")
    public void receive(List<PaymentResponse> messages,
        @Header(name = "Authorization", required = false) String authorizationHeader,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {

        if (authorizationHeader != null) {
            // ⚠️ 헤더 **값**을 찍으면 안 된다 — 유효한 액세스 토큰이 평문으로 남고,
            //    로그를 읽을 수 있는 사람은 만료 전까지 그 사용자로 행세할 수 있다.
            //    있는지 없는지만 남긴다(pay-service 가 먼저 이렇게 고쳤는데, 이 코드를
            //    복사해 간 여기와 order-service 는 그대로였다. ConfigurationGuardrailTest
            //    B-52 가 이제 셋을 함께 본다).
            log.info("Received Authorization Header: {}", "present");
            // 여기에서 토큰 유효성 검증 또는 사용자 정보 추출 로직을 추가할 수 있습니다.
            // 예를 들어, Bearer Token에서 'Bearer ' 부분을 제거하고 순수 토큰만 사용
            String token = authorizationHeader.replace("Bearer ", "");
        
            // 실패 인덱스 보고 → 성공분 커밋 + 실패 레코드만 payment.DLT 격리 (KafkaDltConfig, docs/11 §3)
            for (int i = 0; i < messages.size(); i++) {
                try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive payment")) {
                    paymentResponseMessageListener.completeCoupon(messages.get(i), token);
                } catch (Exception e) {
                    throw new org.springframework.kafka.listener.BatchListenerFailedException(
                            "payment(coupon) 처리 실패", e, i);
                }
            }
        }
        else 
        {
           log.warn("Authorization header not found in the message batch.");
        }
    }
}
