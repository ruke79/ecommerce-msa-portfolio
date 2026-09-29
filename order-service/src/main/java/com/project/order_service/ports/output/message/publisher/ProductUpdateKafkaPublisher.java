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

import com.project.common.message.dto.request.CouponUseRequest;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.request.ProductUpdateRequest;
import com.project.common.outbox.OutboxStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductUpdateKafkaPublisher {

    private final KafkaTemplate<String, ProductUpdateRequest> kafkaTemplate;

    public void publish(ProductUpdateRequest request) {

                
            try {

                // 키 = eventId("order-<id>"). 한 메시지가 주문의 상품 여러 개를 담으므로 상품 단위 키는
                // 없고, 같은 주문의 재고 요청(원본과 재전달)이 같은 파티션으로 가게 한다(docs/38 §1.15).
                // 환불·반품 입고는 HTTP 요청에서 트랜잭션 없이 부른다 — 트랜잭션 프로듀서라 그대로 보내면
                // "No transaction is in process" 로 실패해 재고가 돌아가지 않았다(TransactionalSends).
                CompletableFuture<SendResult<String, ProductUpdateRequest>> kafkaResultFuture =
                        com.project.common.kafka.TransactionalSends.run(kafkaTemplate,
                                ops -> ops.send("product-update", request.getEventId(), request));
                
                kafkaResultFuture.whenComplete((result, ex) -> {
                    if (ex == null) {                   
        
                    }
                    else {
                        log.error (ex.getMessage());
                    }
                });                   
    
            } catch(KafkaException e) {
    
                log.error("Error while sending ProductUpdateRequest" +
                " to kafka with error: {}", e.getMessage());                

            }
        
    }
}
