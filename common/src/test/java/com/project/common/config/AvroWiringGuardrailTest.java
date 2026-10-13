package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사가 토픽({@code order}, {@code payment})의 양 끝이 <b>Avro 로 묶여 있는지</b>를 지킨다.
 *
 * <p>전선 형식은 프로듀서와 소비자가 함께 바뀌어야 한다. 소비자는 Avro·JSON 을 둘 다 읽으므로 어느
 * 쪽이 먼저 바뀌어도 안전하지만, 그건 <i>이행기</i>의 안전이다. 누군가 편의상 프로듀서를
 * {@code JsonSerializer} 로 되돌리면 컴파일도 기능도 그대로라 — 하이브리드 역직렬화기가 조용히
 * 받아 주니까 — 전선이 JSON 으로 돌아간 것을 아무도 모른다. 그래서 파일 단위로 못박는다.
 */
@DisplayName("Avro 배선 가드레일 · order/payment 의 프로듀서와 소비자")
class AvroWiringGuardrailTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    /** 파일 → 그 파일에 반드시 있어야 하는 문자열. */
    private static final Map<String, List<String>> REQUIRED = Map.ofEntries(
            // 프로듀서
            Map.entry("order-service/src/main/java/com/project/order_service/config/KafkaProducerConfig.java",
                    List.of("new AvroDtoSerializer<>(new PaymentRequestAvroMapping())",
                            "new AvroDtoSerializer<>(new CouponRollbackRequestAvroMapping())",
                            "new AvroDtoSerializer<>(new CartRollbackRequestAvroMapping())",
                            "new AvroDtoSerializer<>(new ProductUpdateRequestAvroMapping())",
                            "new AvroDtoSerializer<>(new CartEmptyRequestAvroMapping())")),
            Map.entry("pay-service/src/main/java/com/project/pay_service/message/config/KafkaProducerConfig.java",
                    List.of("new AvroDtoSerializer<>(new PaymentResponseAvroMapping())")),
            Map.entry("user-service/src/main/java/com/project/userservice/message/config/KafkaProducerConfig.java",
                    List.of("new AvroDtoSerializer<>(new UserCreatedRequestAvroMapping())")),
            Map.entry("user-service/src/main/java/com/project/userservice/message/producer/OutgoingMailProducer.java",
                    List.of("new AvroDtoSerializer<>(new OutgoingMailRequestAvroMapping())")),
            Map.entry("coupon-service/src/main/java/com/project/coupon_service/config/KafkaProducerConfig.java",
                    List.of("new AvroDtoSerializer<>(new CouponUseRequestAvroMapping())")),
            Map.entry("chat-service/src/main/java/com/project/chat_service/common/config/kafka/KafkaProducerConfig.java",
                    List.of("new AvroDtoSerializer<>(new ChatMessageAvroMapping())")),
            // DTO 소비자
            Map.entry("pay-service/src/main/java/com/project/pay_service/message/config/KafkaConsumerConfig.java",
                    List.of("new AvroDtoDeserializer<>(new PaymentRequestAvroMapping())")),
            Map.entry("order-service/src/main/java/com/project/order_service/config/KafkaConsumerConfig.java",
                    // user-created 는 더 받지 않는다 — 요청자 신원은 User-Id 헤더다(docs/48 §6-①).
                    List.of("new AvroDtoDeserializer<>(new PaymentResponseAvroMapping())")),
            Map.entry("coupon-service/src/main/java/com/project/coupon_service/config/KafkaConsumerConfig.java",
                    List.of("new AvroDtoDeserializer<>(new PaymentResponseAvroMapping())",
                            "new AvroDtoDeserializer<>(new UserCreatedRequestAvroMapping())",
                            "new AvroDtoDeserializer<>(new CouponRollbackRequestAvroMapping())")),
            Map.entry("cart-service/src/main/java/com/project/cart_service/config/KafkaConsumerConfig.java",
                    List.of("new AvroDtoDeserializer<>(new CouponUseRequestAvroMapping())",
                            "new AvroDtoDeserializer<>(new CartEmptyRequestAvroMapping())",
                            "new AvroDtoDeserializer<>(new CartRollbackRequestAvroMapping())")),
            Map.entry("catalog-service/src/main/java/com/project/catalog_service/message/config/KafkaConsumerConfig.java",
                    List.of("new AvroDtoDeserializer<>(new ProductUpdateRequestAvroMapping())")),
            Map.entry("chat-service/src/main/java/com/project/chat_service/common/config/kafka/KafkaConsumerConfig.java",
                    // user-created 가 빠져 있어 가입 이벤트가 전부 DLT 로 갔다(docs/48 §6-⑤).
                    List.of("new AvroDtoDeserializer<>(new ChatMessageAvroMapping())",
                            "new AvroDtoDeserializer<>(new com.project.common.kafka.avro.UserCreatedRequestAvroMapping())")),
            // Kafka Streams 프로젝션 — 입력 둘은 하이브리드로, 출력은 레코드 serde 로
            Map.entry("saga-projection/src/main/java/com/project/saga_projection/topology/SagaTopology.java",
                    List.of("new AvroDtoDeserializer<>(new PaymentRequestAvroMapping())",
                            "new AvroDtoDeserializer<>(new PaymentResponseAvroMapping())",
                            "new AvroSpecificSerde<>(OrderSagaState.getEncoder(), OrderSagaState.getDecoder())",
                            // 주문 상태 변경 입력 · 진행 타임라인 출력(docs/76) — DTO 없이 레코드 serde
                            "new AvroSpecificSerde<>(OrderStatusChanged.getEncoder(), OrderStatusChanged.getDecoder())",
                            "new AvroSpecificSerde<>(OrderTimeline.getEncoder(), OrderTimeline.getDecoder())")),
            // 문자열 소비자 — Avro 를 JSON 문자열로 바꿔 주지 않으면 JsonNode 파싱에서 깨진다
            Map.entry("delivery-service/src/main/java/com/project/delivery_service/config/KafkaConsumerConfig.java",
                    List.of("new AvroJsonTextDeserializer()")),
            Map.entry("notification-service/src/main/java/com/project/notification_service/config/KafkaConsumerConfig.java",
                    List.of("new AvroJsonTextDeserializer()")));

    @Test
    @DisplayName("Avro 토픽을 내고 받는 설정 파일 전부가 Avro 직렬화기/역직렬화기를 쓴다")
    void sagaEndpointsAreWiredForAvro() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : REQUIRED.entrySet()) {
            Path p = repoRoot().resolve(e.getKey());
            assertThat(p).as(e.getKey()).exists();
            String body = Files.readString(p, StandardCharsets.UTF_8);
            for (String needle : e.getValue()) {
                if (!body.contains(needle)) {
                    offenders.add(e.getKey() + " → '" + needle + "' 없음 (JSON 으로 되돌아갔는가?)");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("스키마 파일과 등록이 짝을 이룬다 — AvroDecoders 의 토픽 수 = 스키마 파일 수, 파일마다 존재")
    void schemasExistForEveryRegisteredTopic() throws IOException {
        Path avro = repoRoot().resolve("common/src/main/avro");
        List<String> schemas;
        try (var files = Files.list(avro)) {
            schemas = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".avsc")).sorted().toList();
        }
        for (String s : List.of("PaymentRequest", "PaymentResponse", "ProductUpdateRequest", "UserCreatedRequest",
                "CartEmptyRequest", "CartRollbackRequest", "CouponUseRequest", "CouponRollbackRequest",
                "OutgoingMailRequest", "ChatMessage", "OrderSagaState", "OrderStatusChanged", "OrderTimeline")) {
            assertThat(schemas).as(s).contains(s + ".avsc");
        }
        assertThat(com.project.common.kafka.avro.AvroDecoders.byTopic()).hasSize(schemas.size());
        // DTO 매핑은 chat(모듈 로컬 DTO)과 DTO 없이 생성 레코드를 그대로 싣는 셋(order-saga-state · order-status · order-timeline,
        // docs/38 §1.17 · docs/76)을 빼고 전부
        assertThat(com.project.common.kafka.avro.AvroMappings.byTopic()).hasSize(schemas.size() - 4);
    }
}
