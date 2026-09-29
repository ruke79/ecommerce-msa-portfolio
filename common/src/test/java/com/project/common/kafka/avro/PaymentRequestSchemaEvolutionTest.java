package com.project.common.kafka.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.MissingSchemaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.message.dto.request.PaymentRequest;

/**
 * 결제 요청 스키마의 진화 — 필드를 더할 때(v3)와 뺄 때(v4) (docs/49 §3, {@link PaymentRequestAvroMapping} 주석).
 *
 * <ul>
 *   <li>v3(제공자 중립 필드 추가): 읽는 쪽이 v3 를 알게 한 릴리스(A) 동안 order 는 v2 작성 스키마로 썼고, B 부터 v3 로.</li>
 *   <li>v4(paypalOrderId 제거): C1 이 v4 를 "다음 스키마" 로 디코더에 먼저 넣었고, C2(지금)부터 v4 로 쓴다.</li>
 * </ul>
 *
 * <p>"옛 소비자" 는 그 스키마만 아는 제네릭 디코더로 흉내 낸다. 옛 레코드는 이력 스키마로 만든다.
 */
@DisplayName("결제 요청 — 스키마 진화")
class PaymentRequestSchemaEvolutionTest {

    private static final Schema V2 = AvroSchemaHistory.schema("avro-history/PaymentRequest-v2.avsc");
    private static final Schema V3 = AvroSchemaHistory.schema("avro-history/PaymentRequest-v3.avsc");
    private static final Schema V4 = com.project.common.avro.PaymentRequest.getClassSchema();

    private static PaymentRequest stripePayment() {
        return PaymentRequest.builder()
                .orderId(123L).customerId(7L).trackingId("trk-123")
                .amounts(new BigDecimal("59.98"))
                .orderStatus(OrderStatus.NOT_PROCESSED).paymentType(PaymentType.STRIPE)
                .paymentStatus(PaymentStatus.COMPLETED)
                .paymentProvider(PaymentProvider.STRIPE).providerPaymentId("cs_123")
                .createdAt(LocalDateTime.of(2026, 9, 25, 0, 0))
                .build();
    }

    private static byte[] write(PaymentRequest dto) {
        return new AvroDtoSerializer<>(new PaymentRequestAvroMapping()).serialize("order", dto);
    }

    private static PaymentRequest read(byte[] bytes) {
        return new AvroDtoDeserializer<>(new PaymentRequestAvroMapping()).deserialize("order", bytes);
    }

    private static long fingerprintOf(byte[] bytes) {
        return ByteBuffer.wrap(bytes, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /** 이력 스키마로 레코드를 만든다 — 앞 릴리스의 order 가 쓴 바이트. 없는 필드는 건너뛴다. */
    private static byte[] encodeWith(Schema schema, Map<String, Object> values) {
        GenericRecordBuilder b = new GenericRecordBuilder(schema);
        values.forEach((name, value) -> {
            Schema.Field f = schema.getField(name);
            if (f == null) {
                return;
            }
            Schema type = f.schema().getType() == Schema.Type.UNION ? f.schema().getTypes().get(1) : f.schema();
            b.set(f, type.getType() == Schema.Type.ENUM ? new GenericData.EnumSymbol(type, value.toString()) : value);
        });
        try {
            return new BinaryMessageEncoder<GenericRecord>(GenericData.get(), schema).encode(b.build()).array();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> paypalValues() {
        return Map.of("orderId", 123L, "customerId", 7L, "trackingId", "trk-123", "amounts", "59.98",
                "paymentType", "PAYPAL", "paypalOrderId", "PP-1",
                "paymentProvider", "PAYPAL", "providerPaymentId", "PP-1");
    }

    // ---------------------------------------------------------------- 지금(v4)

    @Test
    @DisplayName("order 는 v4 로 쓴다 — 참조는 providerPaymentId 하나다")
    void theProducerWritesV4() {
        byte[] bytes = write(stripePayment());

        assertThat(fingerprintOf(bytes)).isEqualTo(SchemaNormalization.parsingFingerprint64(V4));
        assertThat(V4.getField("paypalOrderId")).isNull();
        PaymentRequest back = read(bytes);
        assertThat(back.getPaymentProvider()).isEqualTo(PaymentProvider.STRIPE);
        assertThat(back.getProviderPaymentId()).isEqualTo("cs_123");
        assertThat(back.getAmounts()).isEqualByComparingTo("59.98");
        assertThat(back.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 25, 0, 0));
    }

    /** 🔴 C1 이 한 일 — 롤링 중 남은 C1 의 소비자(생성 클래스 v3 + 저장소에 v4)가 C2 의 레코드를 읽는다. */
    @Test
    @DisplayName("🔴 C1 의 소비자(v3 + 다음 스키마 v4)는 C2 의 레코드를 읽는다")
    void aC1ConsumerReadsV4() throws Exception {
        BinaryMessageDecoder<GenericRecord> c1 = new BinaryMessageDecoder<>(GenericData.get(), V3);
        c1.addSchema(V4);

        GenericRecord r = c1.decode(write(stripePayment()));

        assertThat(r.get("trackingId").toString()).isEqualTo("trk-123");
        assertThat(r.get("providerPaymentId").toString()).isEqualTo("cs_123");
        assertThat(r.get("paypalOrderId")).isNull();
    }

    /** C1 을 건너뛰면 안 되는 이유 — v3 만 아는 소비자는 v4 지문을 모른다. order V20 이 그 배포를 막는다. */
    @Test
    @DisplayName("v3 만 아는 소비자는 v4 레코드를 읽지 못한다 — 그래서 C1 을 건너뛰면 안 된다")
    void aV3OnlyConsumerCannotReadV4() {
        byte[] v4 = write(stripePayment());

        assertThatThrownBy(() -> new BinaryMessageDecoder<GenericRecord>(GenericData.get(), V3).decode(v4))
                .isInstanceOf(MissingSchemaException.class);
    }

    // ---------------------------------------------------------------- 옛 레코드(토픽·DLT 보존 기간 동안)

    @Test
    @DisplayName("B~C1 의 order 가 쓴 v3 레코드를 읽는다 — 옛 paypalOrderId 는 버린다")
    void readsV3Records() {
        PaymentRequest back = read(encodeWith(V3, paypalValues()));

        assertThat(back.getPaymentProvider()).isEqualTo(PaymentProvider.PAYPAL);
        assertThat(back.getProviderPaymentId()).isEqualTo("PP-1");
    }

    /** B 이전의 order 가 쓴 v2 레코드 — 제공자 필드가 없고, PayPal 참조가 있던 필드는 v4 에서 빠졌다. C3 에서 이 경로를 지운다. */
    @Test
    @DisplayName("A 의 order 가 쓴 v2 레코드도 읽는다 — 제공자 필드는 비어 있다")
    void readsV2Records() {
        PaymentRequest back = read(encodeWith(V2, paypalValues()));

        assertThat(back.getTrackingId()).isEqualTo("trk-123");
        assertThat(back.getPaymentType()).isEqualTo(PaymentType.PAYPAL);
        assertThat(back.getPaymentProvider()).isNull();
        assertThat(back.getProviderPaymentId()).isNull();
    }

    // ---------------------------------------------------------------- 진화 규칙

    /** 필드를 더할 때(v3)는 끝에만 붙였다 — 한 릴리스 동안 옛 작성 스키마로 쓰려면 위치가 같아야 했다. */
    @Test
    @DisplayName("v3 는 v2 의 필드를 같은 위치에 두고 끝에만 더했다")
    void v3AppendedOnly() {
        for (Schema.Field f : V2.getFields()) {
            assertThat(V3.getField(f.name()).pos()).as(f.name()).isEqualTo(f.pos());
        }
    }

    /** v4 는 v3 에서 paypalOrderId 만 뺀 것이다 — C1 이 디코더에 넣었던 "다음 스키마" 와 같은 지문이어야 한다. */
    @Test
    @DisplayName("v4 는 v3 에서 paypalOrderId 만 뺀 것이다")
    void v4OnlyDropsPaypalOrderId() {
        assertThat(V4.getFields().stream().map(Schema.Field::name))
                .containsExactlyElementsOf(V3.getFields().stream().map(Schema.Field::name)
                        .filter(n -> !n.equals("paypalOrderId")).toList());
        for (Schema.Field f : V4.getFields()) {
            assertThat(SchemaNormalization.toParsingForm(f.schema())).as(f.name())
                    .isEqualTo(SchemaNormalization.toParsingForm(V3.getField(f.name()).schema()));
        }
    }
}
