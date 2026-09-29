package com.project.common.kafka.avro;

import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.specific.SpecificRecord;

/**
 * 토픽 → 스키마 디코더. 스키마가 있는 <b>모든</b> 토픽이다 — DTO 가 common 에 있든 없든.
 *
 * <p>{@link AvroMappings} 가 "DTO 소비자"용(토픽 → DTO 변환)이라면 이것은 "문자열 소비자"용이다.
 * 새 토픽을 Avro 로 옮기면 여기 한 줄을 더한다 — {@code AvroWiringGuardrailTest} 가 스키마 파일과
 * 대조한다.
 */
public final class AvroDecoders {

    private AvroDecoders() {
    }

    public static Map<String, BinaryMessageDecoder<? extends SpecificRecord>> byTopic() {
        return Map.ofEntries(
                Map.entry("order", com.project.common.avro.PaymentRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.PAYMENT_REQUEST))),
                Map.entry("payment", com.project.common.avro.PaymentResponse.getDecoder()),
                Map.entry("product-update", com.project.common.avro.ProductUpdateRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.PRODUCT_UPDATE))),
                Map.entry("user-created", com.project.common.avro.UserCreatedRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.USER_CREATED))),
                Map.entry("cart-empty", com.project.common.avro.CartEmptyRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.CART_EMPTY))),
                Map.entry("cart-rollback", com.project.common.avro.CartRollbackRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.CART_ROLLBACK))),
                Map.entry("coupon-use", com.project.common.avro.CouponUseRequest.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.COUPON_USE))),
                Map.entry("coupon-rollback", com.project.common.avro.CouponRollbackRequest.getDecoder()),
                Map.entry("outgoing-mail", com.project.common.avro.OutgoingMailRequest.getDecoder()),
                // 옛 스키마(v1·v2)로 쓴 레코드도 읽는다 — 배포 도중 옛 chat 파드가 쓴 것(AvroSchemaHistory).
                Map.entry("chat", com.project.common.avro.ChatMessage.createDecoder(
                        AvroSchemaHistory.store(AvroSchemaHistory.CHAT_MESSAGE))),
                // saga-projection(Kafka Streams)의 출력. DTO 가 없다 — 스키마가 곧 읽기 모델이다(docs/38 §1.17).
                Map.entry("order-saga-state", com.project.common.avro.OrderSagaState.getDecoder()));
    }
}
