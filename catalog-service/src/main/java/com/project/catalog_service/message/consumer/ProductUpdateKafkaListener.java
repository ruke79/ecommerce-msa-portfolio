package com.project.catalog_service.message.consumer;

import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.project.common.message.dto.request.ProductUpdateRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductUpdateKafkaListener {

    private final ProductUpdateMessageListener productUpdateMessageListener;
    /** 레코드 헤더의 traceparent 로 보낸 쪽의 추적을 이어받는다(docs/51 §4h). */
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id="product", topics="product-update")
    void receive(List<ProductUpdateRequest> messages,
            // 레코드마다 보낸 쪽의 추적을 이어받는다(docs/51 §4h). 배치 리스너에는 컨슈머 추적이 걸리지 않는다.
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> kafkaHeaders) {

        // 실패한 레코드의 배치 내 인덱스를 BatchListenerFailedException 으로 알려야
        // DefaultErrorHandler 가 앞의 성공분 오프셋은 커밋하고, 실패 레코드만 재시도/DLT 격리 후
        // 나머지를 재전달한다. (forEach 로 통짜 실패시키면 성공분까지 중복 처리된다)
        for (int i = 0; i < messages.size(); i++) {
            try (com.project.common.outbox.OutboxTraceContext.TraceScope trace = outboxTraceContext.openFrom(
                    com.project.common.outbox.OutboxTraceContext.recordHeaders(kafkaHeaders, i), "receive product-update")) {
                productUpdateMessageListener.productUpdated(messages.get(i));
            } catch (Exception e) {
                throw new org.springframework.kafka.listener.BatchListenerFailedException(
                        "product-update 처리 실패", e, i);
            }
        }
    }

}
