package com.project.common.kafka.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.SchemaStore;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.OrderStatus;
import com.project.common.constants.PaymentStatus;
import com.project.common.constants.PaymentType;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;

/**
 * 전선(wire) 형식 — Avro 단일 객체 인코딩과 JSON 하위호환.
 *
 * <p>여기서 고정하는 것: (1) DTO → Avro → DTO 가 손실이 없다, (2) 예전 JSON 프로듀서의 바이트도 같은
 * DTO 로 읽힌다, (3) 스키마가 진화해도(새 필드) 옛 작성 스키마의 레코드를 읽는다 — 레지스트리 없이
 * {@link SchemaStore} 로, (4) 문자열 소비자용 변환은 토픽별로만 개입한다.
 */
@DisplayName("Avro 전선 형식")
class AvroWireTest {

    private static PaymentRequest sampleRequest() {
        return PaymentRequest.builder()
                .orderId(123L).customerId(7L).trackingId("trk-123")
                .couponName(null).amounts(new BigDecimal("59.98"))
                .orderStatus(OrderStatus.NOT_PROCESSED).paymentType(PaymentType.PAYPAL)
                .paymentStatus(PaymentStatus.COMPLETED)
                .createdAt(LocalDateTime.of(2026, 7, 28, 0, 0))
                .build();
    }

    @Test
    @DisplayName("PaymentRequest: DTO → Avro 바이트 → DTO 가 같다, 바이트는 단일 객체 마커로 시작한다")
    void paymentRequest_roundTrip() {
        AvroDtoSerializer<PaymentRequest> ser = new AvroDtoSerializer<>(new PaymentRequestAvroMapping());
        AvroDtoDeserializer<PaymentRequest> de = new AvroDtoDeserializer<>(new PaymentRequestAvroMapping());

        byte[] bytes = ser.serialize("order", sampleRequest());
        assertThat(AvroWire.isAvro(bytes)).isTrue();
        assertThat(bytes[0]).isEqualTo((byte) 0xC3);

        PaymentRequest back = de.deserialize("order", bytes);
        assertThat(back.getOrderId()).isEqualTo(123L);
        assertThat(back.getCustomerId()).isEqualTo(7L);
        assertThat(back.getAmounts()).isEqualByComparingTo("59.98");
        assertThat(back.getAmounts().toPlainString()).isEqualTo("59.98");   // 자릿수도 그대로
        assertThat(back.getOrderStatus()).isEqualTo(OrderStatus.NOT_PROCESSED);
        assertThat(back.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(back.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 28, 0, 0));
        assertThat(back.getCouponName()).isNull();
    }

    @Test
    @DisplayName("PaymentResponse 도 같다")
    void paymentResponse_roundTrip() {
        PaymentResponse dto = PaymentResponse.builder()
                .paymentId(9L).orderId(123L).customerId(7L).couponName("WELCOME")
                .amounts(new BigDecimal("100")).paymentStatus(PaymentStatus.FAILED).build();
        byte[] bytes = new AvroDtoSerializer<>(new PaymentResponseAvroMapping()).serialize("payment", dto);
        PaymentResponse back = new AvroDtoDeserializer<>(new PaymentResponseAvroMapping()).deserialize("payment", bytes);
        assertThat(back.getPaymentId()).isEqualTo(9L);
        assertThat(back.getOrderId()).isEqualTo(123L);
        assertThat(back.getCouponName()).isEqualTo("WELCOME");
        assertThat(back.getAmounts()).isEqualByComparingTo("100");
        assertThat(back.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("예전 프로듀서의 JSON(spring JsonSerializer 바이트, __TypeId__ 헤더 포함)도 같은 DTO 로 읽는다")
    void jsonFromOldProducer_isStillAccepted() {
        try (JsonSerializer<PaymentRequest> oldProducer = new JsonSerializer<>()) {
            RecordHeaders headers = new RecordHeaders();
            byte[] json = oldProducer.serialize("order", headers, sampleRequest());
            assertThat(AvroWire.isAvro(json)).isFalse();
            assertThat(json[0]).isEqualTo((byte) '{');

            PaymentRequest back = new AvroDtoDeserializer<>(new PaymentRequestAvroMapping())
                    .deserialize("order", headers, json);
            assertThat(back.getOrderId()).isEqualTo(123L);
            assertThat(back.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 28, 0, 0));
        }
    }

    @Test
    @DisplayName("CDC/e2e 가 손으로 쓴 JSON(헤더 없음)도 읽는다")
    void handWrittenJson_withoutHeaders() {
        String payload = "{\"orderId\":123,\"customerId\":7,\"amounts\":59.98,\"orderStatus\":\"NOT_PROCESSED\","
                + "\"paymentType\":\"PAYPAL\",\"paymentStatus\":\"COMPLETED\",\"createdAt\":\"2026-07-28T00:00:00\","
                + "\"authorizationToken\":\"testtoken\"}";
        PaymentRequest back = new AvroDtoDeserializer<>(new PaymentRequestAvroMapping())
                .deserialize("order", payload.getBytes(StandardCharsets.UTF_8));
        assertThat(back.getOrderId()).isEqualTo(123L);
        assertThat(back.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    @DisplayName("스키마 진화: 필드가 하나 적은 옛 작성 스키마의 레코드를 SchemaStore 로 읽는다 — 레지스트리 없이")
    void schemaEvolution_readsOlderWriterSchema() throws IOException {
        // 옛 스키마 = 지금 스키마에서 authorizationToken 을 뺀 것(예전 배포가 썼다고 치자).
        Schema current = com.project.common.avro.PaymentResponse.getClassSchema();
        Schema older = new Schema.Parser().parse(
                "{\"type\":\"record\",\"name\":\"PaymentResponse\",\"namespace\":\"com.project.common.avro\",\"fields\":["
                + "{\"name\":\"paymentId\",\"type\":[\"null\",\"long\"],\"default\":null},"
                + "{\"name\":\"orderId\",\"type\":\"long\"},"
                + "{\"name\":\"customerId\",\"type\":[\"null\",\"long\"],\"default\":null}]}");
        assertThat(older.getFields()).hasSizeLessThan(current.getFields().size());

        GenericRecord old = new GenericData.Record(older);
        old.put("paymentId", 1L);
        old.put("orderId", 123L);
        old.put("customerId", 7L);
        ByteBuffer bytes = new BinaryMessageEncoder<GenericRecord>(GenericData.get(), older).encode(old);
        byte[] data = new byte[bytes.remaining()];
        bytes.get(data);
        assertThat(AvroWire.isAvro(data)).isTrue();

        // 기본 디코더는 자기 지문만 안다 → 옛 지문의 레코드는 못 읽는다.
        SchemaStore.Cache store = new SchemaStore.Cache();
        store.addSchema(older);
        com.project.common.avro.PaymentResponse read =
                com.project.common.avro.PaymentResponse.createDecoder(store).decode(ByteBuffer.wrap(data));

        assertThat(read.getOrderId()).isEqualTo(123L);
        assertThat(read.getPaymentId()).isEqualTo(1L);
        assertThat(read.getCouponName()).isNull();        // 새 필드는 default(null)
        assertThat(read.getPaymentStatus()).isNull();
    }

    @Test
    @DisplayName("문자열 소비자용: 스키마가 있는 토픽의 Avro 는 JSON 문자열로, JSON 은 그대로")
    void textDeserializer_convertsOnlyMappedAvro() throws Exception {
        AvroJsonTextDeserializer text = new AvroJsonTextDeserializer();
        byte[] avro = new AvroDtoSerializer<>(new PaymentResponseAvroMapping()).serialize("payment",
                PaymentResponse.builder().orderId(123L).customerId(7L).paymentStatus(PaymentStatus.COMPLETED).build());

        JsonNode node = new ObjectMapper().readTree(text.deserialize("payment", avro));
        assertThat(node.get("orderId").asLong()).isEqualTo(123L);
        assertThat(node.get("customerId").asLong()).isEqualTo(7L);
        assertThat(node.get("paymentStatus").asText()).isEqualTo("COMPLETED");   // delivery 가 "COMPLET" 을 찾는다

        String chat = "{\"roomId\":\"r1\"}";
        assertThat(text.deserialize("chat", chat.getBytes(StandardCharsets.UTF_8))).isEqualTo(chat);
        assertThat(text.deserialize("payment", chat.getBytes(StandardCharsets.UTF_8))).isEqualTo(chat);   // JSON 이면 그대로

        // DTO 매핑은 common 에 DTO 가 있는 토픽 전부, 디코더는 chat 까지 — 둘의 차이가 정확히 chat 하나다.
        assertThat(AvroMappings.byTopic()).containsOnlyKeys("order", "payment", "product-update", "user-created",
                "cart-empty", "cart-rollback", "coupon-use", "coupon-rollback", "outgoing-mail");
        assertThat(AvroDecoders.byTopic().keySet()).containsAll(AvroMappings.byTopic().keySet()).contains("chat");
    }

    // ── 나머지 토픽들 — 매핑마다 왕복 무손실 ────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <D> D roundTrip(String topic, AvroMapping mapping, D dto) {
        byte[] bytes = new AvroDtoSerializer<D>(mapping).serialize(topic, dto);
        assertThat(AvroWire.isAvro(bytes)).as(topic).isTrue();
        return (D) new AvroDtoDeserializer<D>(mapping).deserialize(topic, bytes);
    }

    @Test
    @DisplayName("product-update: 상품 목록(배열)·멱등 키가 그대로 (토큰 필드는 v2 에서 뺐다)")
    void productUpdate_roundTrip() {
        com.project.common.message.dto.request.ProductUpdateRequest dto = new com.project.common.message.dto.request.ProductUpdateRequest();
        dto.setOrderProducts(java.util.List.of(
                new com.project.common.message.dto.request.OrderProductRequest(11L, 2, "M", 5L),
                new com.project.common.message.dto.request.OrderProductRequest(12L, 1, null, null)));
        dto.setEventId("order-123");
        var back = roundTrip("product-update", new ProductUpdateRequestAvroMapping(), dto);
        assertThat(back.getOrderProducts()).hasSize(2);
        assertThat(back.getOrderProducts().get(0).getProductId()).isEqualTo(11L);
        assertThat(back.getOrderProducts().get(0).getSize()).isEqualTo("M");
        assertThat(back.getOrderProducts().get(1).getColorId()).isNull();
        assertThat(back.getEventId()).isEqualTo("order-123");
        assertThat(back.isRestock()).as("기본은 차감").isFalse();
    }

    /** 재고 복원(환불·반품 입고)은 같은 레코드에 restock=true 다. 기본 false 라 옛 소비자는 차감으로 읽는다. */
    @Test
    @DisplayName("product-update: restock=true 가 그대로 건너간다")
    void productUpdate_restock_roundTrip() {
        com.project.common.message.dto.request.ProductUpdateRequest dto = new com.project.common.message.dto.request.ProductUpdateRequest();
        dto.setOrderProducts(java.util.List.of(
                new com.project.common.message.dto.request.OrderProductRequest(11L, 2, "M", 5L)));
        dto.setEventId("restock-123");
        dto.setRestock(true);
        var back = roundTrip("product-update", new ProductUpdateRequestAvroMapping(), dto);
        assertThat(back.isRestock()).isTrue();
        assertThat(back.getEventId()).isEqualTo("restock-123");
    }

    @Test
    @DisplayName("user-created · cart-empty · cart-rollback · coupon-use · coupon-rollback")
    void sagaSideTopics_roundTrip() {
        var user = roundTrip("user-created", new UserCreatedRequestAvroMapping(),
                com.project.common.message.dto.request.UserCreatedRequest.builder().userId(7L).nickname("n").build());
        assertThat(user.getUserId()).isEqualTo(7L);
        assertThat(user.getNickname()).isEqualTo("n");
        assertThat(user.getImage()).isNull();

        var empty = roundTrip("cart-empty", new CartEmptyRequestAvroMapping(),
                new com.project.common.message.dto.request.CartEmptyRequest(7L, true));
        assertThat(empty.getUserId()).isEqualTo(7L);
        assertThat(empty.isEmptyCart()).isTrue();
        assertThat(empty.getItems()).isEmpty();   // 옛 발행자 — 품목 없음 = 전부 비움

        // 품목을 실은 새 발행자: 상품·사이즈·색상·수량이 그대로 건너간다.
        var lines = roundTrip("cart-empty", new CartEmptyRequestAvroMapping(),
                new com.project.common.message.dto.request.CartEmptyRequest(7L, true, java.util.List.of(
                        new com.project.common.message.dto.request.CartEmptyItem(10L, "M", 5L, 2),
                        new com.project.common.message.dto.request.CartEmptyItem(11L, null, null, 1)), 55L));
        assertThat(lines.getItems()).hasSize(2);
        assertThat(lines.getOrderId()).as("v3 — 주문 id(재전달을 cart 가 거른다)").isEqualTo(55L);
        assertThat(lines.getItems().get(0).getProductId()).isEqualTo(10L);
        assertThat(lines.getItems().get(0).getSize()).isEqualTo("M");
        assertThat(lines.getItems().get(0).getColorId()).isEqualTo(5L);
        assertThat(lines.getItems().get(0).getQty()).isEqualTo(2);
        assertThat(lines.getItems().get(1).getSize()).isNull();

        var rollback = roundTrip("cart-rollback", new CartRollbackRequestAvroMapping(),
                new com.project.common.message.dto.request.CartRollbackRequest(7L, 3));
        assertThat(rollback.getQty()).isEqualTo(3);
        assertThat(rollback.getUserId()).isEqualTo(7L);

        var use = roundTrip("coupon-use", new CouponUseRequestAvroMapping(),
                com.project.common.message.dto.request.CouponUseRequest.builder()
                        .userId(7L).discount(10).totalAfterDiscount(new BigDecimal("89.10")).build());
        assertThat(use.getDiscount()).isEqualTo(10);
        assertThat(use.getTotalAfterDiscount().toPlainString()).isEqualTo("89.10");

        var cr = roundTrip("coupon-rollback", new CouponRollbackRequestAvroMapping(),
                com.project.common.message.dto.request.CouponRollbackRequest.builder().userId(7L).couponName("WELCOME").orderId(42L).build());
        assertThat(cr.getCouponName()).isEqualTo("WELCOME");
        assertThat(cr.getOrderId()).as("v2 — 보상하는 주문").isEqualTo(42L);
    }

    @Test
    @DisplayName("outgoing-mail: params 맵(자격증명 포함)이 그대로, 문자열 소비자에겐 JSON 객체로 보인다")
    void outgoingMail_roundTrip_andTextShape() throws Exception {
        var mail = com.project.common.message.dto.request.OutgoingMailRequest.builder()
                .sourceEventId("evt-1").toEmail("a@b.c").recipientUserId(7L).kind("REGISTRATION_CONFIRM")
                .params(Map.of("confirmationUrl", "https://x/y?token=abc")).locale("ko").build();
        var back = roundTrip("outgoing-mail", new OutgoingMailRequestAvroMapping(), mail);
        assertThat(back.getParams()).containsEntry("confirmationUrl", "https://x/y?token=abc");
        assertThat(back.getKind()).isEqualTo("REGISTRATION_CONFIRM");

        byte[] bytes = new AvroDtoSerializer<>(new OutgoingMailRequestAvroMapping()).serialize("outgoing-mail", mail);
        JsonNode node = new ObjectMapper().readTree(new AvroJsonTextDeserializer().deserialize("outgoing-mail", bytes));
        assertThat(node.get("toEmail").asText()).isEqualTo("a@b.c");
        assertThat(node.get("params").get("confirmationUrl").asText()).isEqualTo("https://x/y?token=abc");   // OutgoingMailListener.params(command)
        assertThat(node.get("recipientUserId").asLong()).isEqualTo(7L);
        assertThat(node.get("sourceEventId").asText()).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("문자열 소비자용 JSON 은 예전 Jackson 출력과 같은 모양이다 — 유니온 벗김, enum 이름, 배열, 날짜는 ISO")
    void textShape_matchesOldJacksonOutput() throws Exception {
        byte[] bytes = new AvroDtoSerializer<>(new PaymentRequestAvroMapping()).serialize("order", sampleRequest());
        JsonNode node = new ObjectMapper().readTree(new AvroJsonTextDeserializer().deserialize("order", bytes));
        assertThat(node.get("orderId").asLong()).isEqualTo(123L);
        assertThat(node.get("customerId").asLong()).isEqualTo(7L);           // NotificationEventListener.longOf(event, "customerId")
        assertThat(node.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(node.get("couponName").isNull()).isTrue();                // {"string": …} 로 감싸지 않는다
        assertThat(node.get("createdAt").asText()).startsWith("2026-07-28T00:00:00");
        assertThat(node.get("amounts").asText()).isEqualTo("59.98");
    }
}
