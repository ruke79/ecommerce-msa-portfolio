package com.project.common.kafka.avro;

import java.util.Map;

import org.apache.avro.specific.SpecificRecord;

/**
 * 어느 토픽이 어느 DTO 변환인가 — 한 곳에.
 *
 * <p>DTO 가 common 에 있는 토픽 전부다. chat 은 DTO(MessageDto)가 chat-service 에 있어 그쪽에서 매핑을
 * 갖고, 문자열 소비자용 디코더는 {@link AvroDecoders} 가 스키마만으로 만든다. 새 토픽을 Avro 로
 * 옮길 때: (1) {@code src/main/avro} 에 스키마, (2) {@code AvroMapping} 구현, (3) 여기와
 * {@link AvroDecoders} 에 한 줄씩, (4) 프로듀서 팩토리의 값 직렬화기, (5) 소비자 팩토리의 값
 * 역직렬화기 — 리스너·서비스는 그대로다. 여기 넣으면 {@code AvroMappingContractTest} 가 DTO 의 모든 필드가
 * 스키마에 있고 왕복해도 살아 돌아오는지 자동으로 검사한다(DTO 에만 필드를 더하면 조용히 사라진다, docs/55 §7-7).
 */
public final class AvroMappings {

    private AvroMappings() {
    }

    public static Map<String, AvroMapping<?, ? extends SpecificRecord>> byTopic() {
        return Map.ofEntries(
                Map.entry("order", new PaymentRequestAvroMapping()),
                Map.entry("payment", new PaymentResponseAvroMapping()),
                Map.entry("product-update", new ProductUpdateRequestAvroMapping()),
                Map.entry("user-created", new UserCreatedRequestAvroMapping()),
                Map.entry("cart-empty", new CartEmptyRequestAvroMapping()),
                Map.entry("cart-rollback", new CartRollbackRequestAvroMapping()),
                Map.entry("coupon-use", new CouponUseRequestAvroMapping()),
                Map.entry("coupon-rollback", new CouponRollbackRequestAvroMapping()),
                Map.entry("outgoing-mail", new OutgoingMailRequestAvroMapping()));
    }
}
