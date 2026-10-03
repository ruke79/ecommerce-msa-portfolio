package com.project.coupon_service.message.consumer;

import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    /**
     * 결제 결과 → 쿠폰 사용 처리(docs/62).
     *
     * <p>🐛 예전: {@code Authorization} 헤더가 있을 때만 처리했다. pay-service 는 그 헤더를 일부러 싣지 않게 바뀌었으므로
     * (스케줄러 스레드의 "Bearer null" 이었다 — docs/54 §1.7) <b>쿠폰이 한 번도 '사용됨' 으로 바뀌지 않았다</b> — 같은 쿠폰으로
     * 주문마다 계속 할인받을 수 있었다. 신원은 메시지의 customerId 이고(결제를 확정한 서버가 채운다), 토큰은 쓰지 않는다.
     */
    // [JA] 決済結果 → クーポン使用処理。以前は Authorization ヘッダーがあるときだけ処理していたが、pay-service はそのヘッダーを
    //      意図的に載せなくなったため、クーポンが一度も「使用済み」にならず、同じクーポンで何度でも割引を受けられた。
    @KafkaListener(id="coupon-used", topics = "payment", containerFactory = "paymentResponseContainerFactory")
    public void receive(List<PaymentResponse> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {

        // 실패 인덱스 보고 → 성공분 커밋 + 실패 레코드만 payment.DLT 격리 (KafkaDltConfig, docs/11 §3)
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive payment")) {
                paymentResponseMessageListener.completeCoupon(messages.get(i));
            } catch (Exception e) {
                throw new org.springframework.kafka.listener.BatchListenerFailedException(
                        "payment(coupon) 처리 실패", e, i);
            }
        }
    }
}
