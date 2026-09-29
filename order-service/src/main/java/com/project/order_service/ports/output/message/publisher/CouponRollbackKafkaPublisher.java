package com.project.order_service.ports.output.message.publisher;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.message.dto.request.CouponRollbackRequest;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponRollbackKafkaPublisher {

    private final KafkaTemplate<String, CouponRollbackRequest> kafkaTemplate;

    @Transactional
    public void publish(CouponRollbackRequest message) {

         String topic = "coupon-rollback";
        
        try {
            // CompletableFuture를 반환하는 send() 메서드를 사용
            // 키 = 사용자 id — 같은 사용자의 쿠폰 사용/롤백은 같은 파티션으로(docs/38 §1.15).
            CompletableFuture<SendResult<String, CouponRollbackRequest>> future =
                    com.project.common.kafka.TransactionalSends.run(kafkaTemplate,
                            ops -> ops.send(topic, String.valueOf(message.getUserId()), message));   // 환불·취소는 트랜잭션 밖에서 부른다
            
            // 성공/실패 콜백을 등록
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    // 메시지 전송 성공 시 로깅
                    log.info("Successfully sent message to topic '{}' with offset {}.",
                            topic, result.getRecordMetadata().offset());
                } else {
                    // 메시지 전송 실패 시 로깅
                    log.error("Failed to send message to topic '{}'. Error: {}", topic, ex.getMessage());
                }
            });

        } catch (KafkaException e) {
            // 메시지가 프로듀서 버퍼에 추가되지 못했을 때 발생하는 예외 처리
            log.error("Error while adding message to Kafka producer buffer for topic '{}': {}", topic, e.getMessage());
        }
    }
}
