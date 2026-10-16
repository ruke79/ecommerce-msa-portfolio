package com.project.common.kafka.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.avro.ChatMessage;
import com.project.common.avro.UserCreatedRequest;

/**
 * 스키마 이력 — 필드를 더하거나 빼도 배포 도중 옛 프로듀서가 쓴 레코드를 새 소비자가 읽는다(docs/48 §6-⑥).
 *
 * <p>레지스트리가 없어 레코드 앞의 지문으로 작성 스키마를 찾는다. 이력이 없으면 지문이 다른 레코드는 전부
 * 역직렬화 실패로 DLT 에 간다 — 채팅 메시지는 방에 전달되지 않고, 가입 쿠폰은 나가지 않는다.
 */
@DisplayName("Avro 스키마 이력 — 옛 레코드를 새 소비자가 읽는다")
class ChatMessageSchemaHistoryTest {

    private static Schema history(String resource) throws Exception {
        try (InputStream in = ChatMessageSchemaHistoryTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new Schema.Parser().parse(in);
        }
    }

    private static byte[] encode(Schema schema, Map<String, Object> fields) throws Exception {
        GenericRecord r = new GenericData.Record(schema);
        fields.forEach(r::put);
        ByteBuffer b = new BinaryMessageEncoder<GenericRecord>(GenericData.get(), schema).encode(r);
        byte[] data = new byte[b.remaining()];
        b.get(data);
        return data;
    }

    private static long fp(Schema s) {
        return SchemaNormalization.parsingFingerprint64(s);
    }

    @Test
    @DisplayName("이력의 스키마는 지금 스키마와 지문이 다르다 — 이력이 없으면 못 읽는다는 뜻이다")
    void fingerprintsDiffer() throws Exception {
        Schema v1 = history("avro-history/ChatMessage-v1.avsc");
        Schema v2 = history("avro-history/ChatMessage-v2.avsc");
        assertThat(java.util.Set.of(fp(v1), fp(v2), fp(ChatMessage.getClassSchema()))).hasSize(3);
        assertThat(fp(history("avro-history/UserCreatedRequest-v1.avsc")))
                .isNotEqualTo(fp(UserCreatedRequest.getClassSchema()));
    }

    @Test
    @DisplayName("🔴 chat v1(발신자=이메일) 레코드 — 읽힌다. 발신자는 모름, 이메일은 버려진다")
    void chatV1IsReadable() throws Exception {
        byte[] data = encode(history("avro-history/ChatMessage-v1.avsc"), Map.of(
                "id", "42", "roomId", "room-1", "email", "old@example.com", "nickname", "옛사람", "createdAt", 1000L));

        ChatMessage read = (ChatMessage) AvroDecoders.byTopic().get("chat").decode(ByteBuffer.wrap(data));
        assertThat(read.getRoomId()).isEqualTo("room-1");
        assertThat(read.getSenderId()).isNull();
        assertThat(read.toString()).doesNotContain("old@example.com");

        JsonNode json = new ObjectMapper().readTree(new AvroJsonTextDeserializer().deserialize("chat", data));
        assertThat(json.get("nickname").asText()).isEqualTo("옛사람");
        assertThat(json.has("email")).isFalse();
    }

    @Test
    @DisplayName("🔴 chat v2(senderId + email) 레코드 — senderId 는 살고 이메일은 버려진다")
    void chatV2IsReadable() throws Exception {
        byte[] data = encode(history("avro-history/ChatMessage-v2.avsc"), Map.of(
                "id", "43", "roomId", "room-1", "senderId", 7L, "nickname", "사람", "createdAt", 1000L));

        ChatMessage read = (ChatMessage) AvroDecoders.byTopic().get("chat").decode(ByteBuffer.wrap(data));
        assertThat(read.getSenderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("🔴 user-created v1(email 있음) 레코드 — DTO 로 읽힌다. 이메일은 버려진다")
    void userCreatedV1IsReadable() throws Exception {
        byte[] data = encode(history("avro-history/UserCreatedRequest-v1.avsc"), Map.of(
                "userId", 42L, "nickname", "새사람", "email", "new@example.com"));

        com.project.common.message.dto.request.UserCreatedRequest dto =
                new AvroDtoDeserializer<>(new UserCreatedRequestAvroMapping()).deserialize("user-created", data);
        assertThat(dto.getUserId()).isEqualTo(42L);
        assertThat(dto.getNickname()).isEqualTo("새사람");
        assertThat(dto.toString()).doesNotContain("new@example.com");
    }

    @Test
    @DisplayName("옛 JSON(email 포함)도 읽는다 — 모르는 필드는 무시한다(CDC·e2e·옛 프로듀서)")
    void legacyJsonWithEmailIsTolerated() {
        String json = "{\"userId\":42,\"nickname\":\"n\",\"email\":\"x@example.com\"}";
        com.project.common.message.dto.request.UserCreatedRequest dto =
                new AvroDtoDeserializer<>(new UserCreatedRequestAvroMapping())
                        .deserialize("user-created", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(dto.getUserId()).isEqualTo(42L);
    }

    /** 🔒 cart-empty·cart-rollback·coupon-use v1 은 사용자 액세스 토큰을 실었다. 새 소비자는 읽되 토큰은 버린다. */
    @Test
    @DisplayName("🔴 토큰을 실은 v1 레코드(cart-empty·cart-rollback·coupon-use)를 읽는다 — 토큰은 버려진다")
    void tokenCarryingV1RecordsAreReadable() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiJ9.secret.sig";

        Schema emptyV1 = history("avro-history/CartEmptyRequest-v1.avsc");
        Schema itemSchema = emptyV1.getField("items").schema().getElementType();
        GenericRecord item = new GenericData.Record(itemSchema);
        item.put("productId", 10L);
        item.put("size", "M");
        item.put("colorId", 5L);
        item.put("qty", 2);
        byte[] empty = encode(emptyV1, Map.of("userId", 7L, "emptyCart", true, "authorizationToken", token,
                "items", java.util.List.of(item)));
        com.project.common.message.dto.request.CartEmptyRequest e =
                new AvroDtoDeserializer<>(new CartEmptyRequestAvroMapping()).deserialize("cart-empty", empty);
        assertThat(e.getUserId()).isEqualTo(7L);
        assertThat(e.getItems()).hasSize(1);

        byte[] rollback = encode(history("avro-history/CartRollbackRequest-v1.avsc"),
                Map.of("userId", 7L, "qty", 1, "authorizationToken", token));
        assertThat(new AvroDtoDeserializer<>(new CartRollbackRequestAvroMapping())
                .deserialize("cart-rollback", rollback).getUserId()).isEqualTo(7L);

        byte[] use = encode(history("avro-history/CouponUseRequest-v1.avsc"),
                Map.of("userId", 7L, "discount", 10, "totalAfterDiscount", "90.00", "authorizationToken", token));
        assertThat(new AvroDtoDeserializer<>(new CouponUseRequestAvroMapping())
                .deserialize("coupon-use", use).getTotalAfterDiscount()).isEqualByComparingTo("90.00");

        // 문자열 소비자(알림 등)용 디코더도 읽고, JSON 으로 옮길 때 토큰이 나오지 않는다.
        for (var entry : Map.of("cart-empty", empty, "cart-rollback", rollback, "coupon-use", use).entrySet()) {
            assertThat(new AvroJsonTextDeserializer().deserialize(entry.getKey(), entry.getValue()))
                    .as(entry.getKey()).doesNotContain(token).doesNotContain("authorizationToken");
        }
    }

    /** 🔒 결제 요청(order)·상품 갱신(product-update) v1 도 사용자 토큰을 실었다 — 새 소비자는 읽되 토큰은 버린다. */
    @Test
    @DisplayName("🔴 토큰을 실은 결제 요청·상품 갱신 v1 을 읽는다 — pay·catalog·saga-projection 이 쓰는 디코더")
    void paymentSagaV1RecordsAreReadable() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiJ9.secret.sig";

        byte[] payment = encode(history("avro-history/PaymentRequest-v1.avsc"),
                Map.of("orderId", 123L, "customerId", 7L, "authorizationToken", token));
        com.project.common.message.dto.request.PaymentRequest p =
                new AvroDtoDeserializer<>(new PaymentRequestAvroMapping()).deserialize("order", payment);
        assertThat(p.getOrderId()).isEqualTo(123L);
        assertThat(p.toString()).doesNotContain(token);

        Schema productV1 = history("avro-history/ProductUpdateRequest-v1.avsc");
        byte[] product = encode(productV1, Map.of("orderProducts", java.util.List.of(),
                "autorizationToken", token, "eventId", "order-123", "restock", false));
        assertThat(new AvroDtoDeserializer<>(new ProductUpdateRequestAvroMapping())
                .deserialize("product-update", product).getEventId()).isEqualTo("order-123");

        for (var entry : Map.of("order", payment, "product-update", product).entrySet()) {
            assertThat(new AvroJsonTextDeserializer().deserialize(entry.getKey(), entry.getValue()))
                    .as(entry.getKey()).doesNotContain(token).doesNotContain("uthorizationToken");
        }
    }

    @Test
    @DisplayName("🔒 지금 스키마에는 사용자 토큰 필드가 없다 — 메시지 전부")
    void currentSchemasCarryNoToken() {
        for (Schema s : java.util.List.of(com.project.common.avro.CartEmptyRequest.getClassSchema(),
                com.project.common.avro.CartRollbackRequest.getClassSchema(),
                com.project.common.avro.CouponUseRequest.getClassSchema(),
                com.project.common.avro.PaymentRequest.getClassSchema(),
                com.project.common.avro.ProductUpdateRequest.getClassSchema())) {
            assertThat(s.getField("authorizationToken")).as(s.getName()).isNull();
            assertThat(s.getField("autorizationToken")).as(s.getName()).isNull();
        }
    }

    @Test
    @DisplayName("없는 이력 파일은 기동에서 실패한다 — 조용히 빈 저장소로 넘어가지 않는다")
    void missingHistoryFails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AvroSchemaHistory.store("avro-history/nope.avsc"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("🔴 coupon-rollback v1(orderId 없음) 레코드 — 새 소비자가 읽는다, 주문은 모름(null → 예전처럼 되돌린다)")
    void couponRollbackV1IsReadable() throws Exception {
        Schema v1 = history("avro-history/CouponRollbackRequest-v1.avsc");
        assertThat(fp(v1)).isNotEqualTo(fp(com.project.common.avro.CouponRollbackRequest.getClassSchema()));
        byte[] data = encode(v1, Map.of("userId", 7L, "couponName", "WELCOME"));

        var read = (com.project.common.avro.CouponRollbackRequest) AvroDecoders.byTopic().get("coupon-rollback")
                .decode(ByteBuffer.wrap(data));
        assertThat(read.getUserId()).isEqualTo(7L);
        assertThat(read.getOrderId()).isNull();
        var dto = new CouponRollbackRequestAvroMapping().decoder().decode(ByteBuffer.wrap(data));
        assertThat(dto.getCouponName().toString()).isEqualTo("WELCOME");
    }

    @Test
    @DisplayName("🔴 cart-empty v2(orderId 없음) 레코드 — 새 소비자가 읽는다, 주문은 모름(null → 거르지 않는다)")
    void cartEmptyV2IsReadable() throws Exception {
        Schema v2 = history("avro-history/CartEmptyRequest-v2.avsc");
        assertThat(fp(v2)).isNotEqualTo(fp(com.project.common.avro.CartEmptyRequest.getClassSchema()));
        byte[] data = encode(v2, Map.of("userId", 7L, "emptyCart", true, "items", java.util.List.of()));

        var dto = new CartEmptyRequestAvroMapping().decoder().decode(ByteBuffer.wrap(data));
        assertThat(dto.getUserId()).isEqualTo(7L);
        assertThat(dto.getOrderId()).isNull();
        var read = (com.project.common.avro.CartEmptyRequest) AvroDecoders.byTopic().get("cart-empty").decode(ByteBuffer.wrap(data));
        assertThat(read.getOrderId()).isNull();
    }
}
