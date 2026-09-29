package com.project.saga_projection.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.SagaPhase;
import com.project.common.constants.PaymentStatus;
import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.AvroWire;
import com.project.common.kafka.avro.PaymentRequestAvroMapping;
import com.project.common.kafka.avro.PaymentResponseAvroMapping;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;

/**
 * 브로커 없이 토폴로지를 끝까지 돌린다: 입력 토픽 둘 → 상태 저장소 → 출력 토픽.
 * EOS 는 TopologyTestDriver 가 흉내 내지 않으므로 여기서 검증하는 것은 <b>접기 규칙과 배선</b>이다 —
 * 어떤 serde 로 어떤 토픽을 읽고, 무엇을 어떤 키로 내는가.
 */
@DisplayName("SagaTopology · order ∪ payment → order-saga-state")
class SagaTopologyTest {

    private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private TestInputTopic<String, PaymentRequest> orderAvro;
    private TestInputTopic<String, PaymentResponse> paymentAvro;
    private TestInputTopic<String, byte[]> orderRaw;
    private TestInputTopic<String, byte[]> paymentRaw;
    private TestOutputTopic<String, OrderSagaState> stateOut;

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        new SagaTopology("order", "payment", "order-saga-state", Clock.fixed(T0, ZoneOffset.UTC)).build(builder);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "saga-projection-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "none:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        driver = new TopologyTestDriver(builder.build(), props);

        orderAvro = driver.createInputTopic("order", new StringSerializer(),
                new AvroDtoSerializer<>(new PaymentRequestAvroMapping()));
        paymentAvro = driver.createInputTopic("payment", new StringSerializer(),
                new AvroDtoSerializer<>(new PaymentResponseAvroMapping()));
        orderRaw = driver.createInputTopic("order", new StringSerializer(), new ByteArraySerializer());
        paymentRaw = driver.createInputTopic("payment", new StringSerializer(), new ByteArraySerializer());
        stateOut = driver.createOutputTopic("order-saga-state", new StringDeserializer(),
                SagaTopology.stateSerde().deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private static PaymentRequest request(long orderId) {
        return PaymentRequest.builder().orderId(orderId).customerId(7L).amounts(new BigDecimal("59.98"))
                .couponName("WELCOME").build();
    }

    private static PaymentResponse response(long orderId, PaymentStatus status) {
        return PaymentResponse.builder().orderId(orderId).paymentId(99L).customerId(7L).paymentStatus(status).build();
    }

    private KeyValueStore<String, OrderSagaState> store() {
        return driver.getKeyValueStore(SagaTopology.STORE_NAME);
    }

    @Test
    @DisplayName("요청(Avro) → 응답(Avro): 출력 토픽에 REQUESTED, COMPLETED 두 변경이 주문 id 키로 나가고 저장소가 최종 상태다")
    void foldsRequestAndResponse() {
        orderAvro.pipeInput("1", request(1));
        paymentAvro.pipeInput("1", response(1, PaymentStatus.COMPLETED));

        List<KeyValue<String, OrderSagaState>> out = stateOut.readKeyValuesToList();
        assertThat(out).extracting(kv -> kv.key).containsExactly("1", "1");
        assertThat(out).extracting(kv -> kv.value.getPhase()).containsExactly(SagaPhase.REQUESTED, SagaPhase.COMPLETED);

        OrderSagaState s = store().get("1");
        assertThat(s.getPhase()).isEqualTo(SagaPhase.COMPLETED);
        assertThat(s.getAmounts()).isEqualTo("59.98");
        assertThat(s.getPaymentId()).isEqualTo(99L);
        assertThat(s.getRequestCount()).isEqualTo(1);
        assertThat(s.getResponseCount()).isEqualTo(1);
        assertThat(s.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("입력 키가 'order-5' 든 '5' 든 주문 id 로 재키잉되어 하나의 상태로 모인다")
    void rekeysByOrderId() {
        orderAvro.pipeInput("order-5", request(5));
        paymentAvro.pipeInput("5", response(5, PaymentStatus.FAILED));

        assertThat(stateOut.readKeyValuesToList()).extracting(kv -> kv.key).containsExactly("5", "5");
        assertThat(store().get("5").getPhase()).isEqualTo(SagaPhase.FAILED);
        assertThat(store().get("order-5")).isNull();
    }

    @Test
    @DisplayName("옛 JsonSerializer 바이트(헤더 없음)도 같은 상태로 접힌다 — 롤링 배포·CDC 릴레이의 JSON")
    void acceptsJsonInput() {
        try (JsonSerializer<PaymentRequest> reqJson = new JsonSerializer<>();
             JsonSerializer<PaymentResponse> resJson = new JsonSerializer<>()) {
            byte[] req = reqJson.serialize("order", request(9));
            byte[] res = resJson.serialize("payment", response(9, PaymentStatus.COMPLETED));
            assertThat(AvroWire.isAvro(req)).isFalse();
            orderRaw.pipeInput("9", req);
            paymentRaw.pipeInput("9", res);
        }
        OrderSagaState s = store().get("9");
        assertThat(s.getPhase()).isEqualTo(SagaPhase.COMPLETED);
        assertThat(s.getAmounts()).isEqualTo("59.98");
    }

    @Test
    @DisplayName("응답이 먼저 오면 ORPHAN_RESPONSE 로 남고, 요청이 오면 응답의 단계로 옮긴다")
    void orphanResponse() {
        paymentAvro.pipeInput("2", response(2, PaymentStatus.CANCELED));
        assertThat(store().get("2").getPhase()).isEqualTo(SagaPhase.ORPHAN_RESPONSE);

        orderAvro.pipeInput("2", request(2));
        assertThat(store().get("2").getPhase()).isEqualTo(SagaPhase.CANCELED);
        assertThat(store().get("2").getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("주문 id 가 없는 레코드는 버린다 — 키를 만들 수 없다")
    void dropsRecordsWithoutOrderId() {
        orderRaw.pipeInput("x", "{}".getBytes(StandardCharsets.UTF_8));
        paymentRaw.pipeInput("x", "{\"paymentStatus\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(stateOut.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("출력은 Avro 단일 객체 인코딩이고 토큰을 싣지 않는다")
    void outputIsAvroWithoutToken() {
        orderAvro.pipeInput("3", request(3));
        TestOutputTopic<String, byte[]> raw = driver.createOutputTopic("order-saga-state",
                new StringDeserializer(), new org.apache.kafka.common.serialization.ByteArrayDeserializer());
        byte[] bytes = raw.readValue();
        assertThat(AvroWire.isAvro(bytes)).isTrue();
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("secret-token-value");
    }

    @Test
    @DisplayName("상태 디렉터리는 애플리케이션 id 아래에 만들어진다 — 파드마다 emptyDir 하나면 된다")
    void stateDirIsPerApplication() throws Exception {
        orderAvro.pipeInput("4", request(4));
        try (var files = Files.list(stateDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).contains("saga-projection-test");
        }
    }
}
