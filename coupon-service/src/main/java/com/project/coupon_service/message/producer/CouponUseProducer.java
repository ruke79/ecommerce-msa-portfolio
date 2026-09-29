package com.project.coupon_service.message.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.message.dto.request.CouponUseRequest;




/**
 * 쿠폰 적용 결과를 cart 에 알린다(장바구니의 할인 후 총액). 토픽 coupon-use.
 *
 * <p>🔒 예전에는 사용자의 액세스 토큰을 Kafka <b>헤더</b>에 실었다({@code Authorization: Bearer …}). 받는 쪽(cart)은
 * 그 헤더를 읽지도 않았고, 토큰은 토픽 보존 기간 동안 브로커에 남았다 — 토픽을 읽을 수 있으면 곧 그 사용자다.
 * 이제 싣지 않는다(docs/54 §10.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponUseProducer {

    private final KafkaTemplate<String, CouponUseRequest> kafkaTemplate;

    @Transactional
    public void publish(CouponUseRequest request) {
        try {
            Message<CouponUseRequest> kafkaMessage = MessageBuilder.withPayload(request)
                    .setHeader(KafkaHeaders.TOPIC, "coupon-use")
                    // 키 = 사용자 id — 같은 사용자의 쿠폰 사용/롤백은 같은 파티션으로(docs/38 §1.15).
                    .setHeader(KafkaHeaders.KEY, String.valueOf(request.getUserId()))
                    .build();

            kafkaTemplate.send(kafkaMessage);
        } catch (KafkaException ex) {
            // 🐛 예전에는 여기서 조용히 삼켰다. 실패하면 쿠폰은 적용됐다고 답했는데 장바구니 총액은 그대로다 — 남긴다.
            log.warn("coupon-use 발행 실패 — 장바구니 할인 총액이 갱신되지 않는다. userId={}", request.getUserId(), ex);
        }
    }
}
