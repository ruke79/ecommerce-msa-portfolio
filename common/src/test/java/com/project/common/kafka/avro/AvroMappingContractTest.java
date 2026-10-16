package com.project.common.kafka.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모든 토픽의 DTO ↔ Avro 매핑이 필드를 잃지 않는다 (docs/55 §7-7, {@link AvroMappingContract}).
 *
 * <p>토픽 목록은 {@link AvroMappings#byTopic()} 에서 온다 — 새 토픽을 Avro 로 옮기면 자동으로 여기 들어온다.
 * chat 의 매핑은 chat-service 에 있어 그쪽 테스트({@code ChatMessageAvroContractTest})가 같은 검사를 한다.
 */
@DisplayName("DTO ↔ Avro 매핑 계약")
class AvroMappingContractTest {

    /** 일부러 싣지 않는 필드 — 토픽 → (경로 → 이유). 이유 없는 제외는 검사가 거절한다. */
    private static final Map<String, Map<String, String>> IGNORED = Map.of();

    @Test
    @DisplayName("🔴 모든 토픽의 매핑이 DTO 의 필드를 전부 싣고, 왕복해도 같은 값이 돌아온다")
    void everyMappingKeepsEveryField() {
        AvroMappings.byTopic().forEach((topic, mapping) -> assertThat(
                AvroMappingContract.verify(mapping, IGNORED.getOrDefault(topic, Map.of())))
                .as("토픽 %s (%s)", topic, mapping.dtoType().getSimpleName())
                .isEmpty());
    }

    /**
     * 검사가 실제로 잡는가 — 결제 요청의 옛 버그를 다시 만든다. 스키마 v2 에는 제공자 필드가 없었고, 매핑은 조용히
     * 버렸다(docs/49 §3). 여기서는 매핑이 {@code providerPaymentId} 를 옮기지 않는 모양으로 흉내 낸다.
     */
    @Test
    @DisplayName("매핑이 필드 하나를 옮기지 않으면 잡는다 — 결제 요청의 옛 버그")
    void catchesAMappingThatDropsAField() {
        PaymentRequestAvroMapping real = new PaymentRequestAvroMapping();
        AvroMapping<com.project.common.message.dto.request.PaymentRequest, com.project.common.avro.PaymentRequest> dropping =
                new AvroMapping<>() {
                    @Override
                    public Class<com.project.common.message.dto.request.PaymentRequest> dtoType() {
                        return real.dtoType();
                    }

                    @Override
                    public com.project.common.avro.PaymentRequest toRecord(
                            com.project.common.message.dto.request.PaymentRequest d) {
                        com.project.common.avro.PaymentRequest r = real.toRecord(d);
                        r.setProviderPaymentId(null);
                        return r;
                    }

                    @Override
                    public com.project.common.message.dto.request.PaymentRequest toDto(
                            com.project.common.avro.PaymentRequest r) {
                        return real.toDto(r);
                    }

                    @Override
                    public BinaryMessageEncoder<com.project.common.avro.PaymentRequest> encoder() {
                        return real.encoder();
                    }

                    @Override
                    public BinaryMessageDecoder<com.project.common.avro.PaymentRequest> decoder() {
                        return real.decoder();
                    }
                };

        assertThat(AvroMappingContract.verify(dropping, Map.of()))
                .anySatisfy(p -> assertThat(p).contains("PaymentRequest.providerPaymentId").contains("받은 값 null"));
    }

    /** DTO 에 필드를 더하고 스키마를 잊은 경우 — 이름 대조가 잡는다. */
    static class RollbackWithExtra {
        Long userId;
        String couponName;
        Long orderId;
        String providerRef;   // 스키마(CouponRollbackRequest)에 없다
    }

    @Test
    @DisplayName("DTO 에만 있는 필드를 잡는다 — 스키마를 잊고 DTO 에만 더한 경우")
    void catchesADtoOnlyField() {
        AvroMapping<RollbackWithExtra, com.project.common.avro.CouponRollbackRequest> mapping = new AvroMapping<>() {
            @Override
            public Class<RollbackWithExtra> dtoType() {
                return RollbackWithExtra.class;
            }

            @Override
            public com.project.common.avro.CouponRollbackRequest toRecord(RollbackWithExtra d) {
                return com.project.common.avro.CouponRollbackRequest.newBuilder()
                        .setUserId(d.userId).setCouponName(d.couponName).setOrderId(d.orderId).build();
            }

            @Override
            public RollbackWithExtra toDto(com.project.common.avro.CouponRollbackRequest r) {
                RollbackWithExtra d = new RollbackWithExtra();
                d.userId = r.getUserId();
                d.couponName = r.getCouponName();
                d.orderId = r.getOrderId();
                return d;
            }

            @Override
            public BinaryMessageEncoder<com.project.common.avro.CouponRollbackRequest> encoder() {
                return com.project.common.avro.CouponRollbackRequest.getEncoder();
            }

            @Override
            public BinaryMessageDecoder<com.project.common.avro.CouponRollbackRequest> decoder() {
                return com.project.common.avro.CouponRollbackRequest.getDecoder();
            }
        };

        assertThat(AvroMappingContract.verify(mapping, Map.of()))
                .anySatisfy(p -> assertThat(p).contains("RollbackWithExtra.providerRef").contains("DTO 에만"));
        // 일부러 싣지 않는 것이라면 이유와 함께 제외한다.
        assertThat(AvroMappingContract.verify(mapping,
                Map.of("RollbackWithExtra.providerRef", "예시 — 이 필드는 이 서비스 안에서만 쓴다"))).isEmpty();
    }

    @Test
    @DisplayName("이유 없는 제외는 받지 않는다")
    void anIgnoreNeedsAReason() {
        assertThat(AvroMappingContract.verify(new PaymentRequestAvroMapping(),
                Map.of("PaymentRequest.couponName", " ")))
                .anySatisfy(p -> assertThat(p).contains("이유"));
    }
}
