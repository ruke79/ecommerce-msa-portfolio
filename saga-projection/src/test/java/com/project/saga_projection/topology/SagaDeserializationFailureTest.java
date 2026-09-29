package com.project.saga_projection.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.StreamsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.SagaPhase;
import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.PaymentRequestAvroMapping;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.saga_projection.config.KafkaStreamsConfig;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 읽지 못한 입력 레코드 — 깨진 것은 건너뛰고, 모르는 스키마면 멈춘다 ({@code SagaDeserializationExceptionHandler}).
 * 운영 설정({@link KafkaStreamsConfig#errorHandling()})을 그대로 드라이버에 준다.
 */
@DisplayName("saga-projection · 역직렬화 실패")
class SagaDeserializationFailureTest {

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private void start(boolean withHandler) {
        StreamsBuilder builder = new StreamsBuilder();
        new SagaTopology("order", "payment", "order-saga-state",
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)).build(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "saga-projection-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "none:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        if (withHandler) {
            props.putAll(KafkaStreamsConfig.errorHandling());
        }
        driver = new TopologyTestDriver(builder.build(), props);
        Metrics.globalRegistry.add(meters);
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
        Metrics.globalRegistry.remove(meters);
    }

    private TestInputTopic<String, byte[]> orderRaw() {
        return driver.createInputTopic("order", new StringSerializer(), new ByteArraySerializer());
    }

    private TestInputTopic<String, PaymentRequest> orderAvro() {
        return driver.createInputTopic("order", new StringSerializer(),
                new AvroDtoSerializer<>(new PaymentRequestAvroMapping()));
    }

    private static PaymentRequest request(long orderId) {
        return PaymentRequest.builder().orderId(orderId).customerId(7L).amounts(new BigDecimal("59.98")).build();
    }

    private double counted(String action) {
        var c = meters.find("saga.projection.deserialization.failures").tag("topic", "order").tag("action", action)
                .counter();
        return c == null ? 0 : c.count();
    }

    /** 아무 소비자도 모르는 작성 스키마 — 결제 요청과 같은 이름이지만 필드가 다르다(= 이 파드보다 새 프로듀서). */
    private static byte[] unknownSchemaRecord() throws IOException {
        Schema future = SchemaBuilder.record("PaymentRequest").namespace("com.project.common.avro").fields()
                .requiredLong("orderId").optionalString("somethingNew").endRecord();
        GenericRecord r = new GenericData.Record(future);
        r.put("orderId", 1L);
        return new BinaryMessageEncoder<GenericRecord>(GenericData.get(), future).encode(r).array();
    }

    /** 🔴 핵심 — 깨진 레코드 하나가 뒤의 주문을 인질로 잡지 않는다. */
    @Test
    @DisplayName("🔴 깨진 레코드는 건너뛰고 다음 주문을 계속 접는다 — 계수기가 오른다")
    void aPoisonRecordIsSkipped() {
        start(true);

        orderRaw().pipeInput("1", "{not json".getBytes(StandardCharsets.UTF_8));
        orderAvro().pipeInput("2", request(2));

        OrderSagaState s = driver.<String, OrderSagaState>getKeyValueStore(SagaTopology.STORE_NAME).get("2");
        assertThat(s.getPhase()).isEqualTo(SagaPhase.REQUESTED);
        assertThat(counted("skipped")).isEqualTo(1);
    }

    /**
     * 🔴 건너뛰면 오프셋이 커밋되어 새 파드도 다시 읽지 않는다 — 프로젝션이 그 기록을 영영 잃는다. 멈추면 롤링 배포 중
     * 새 파드가 마지막 커밋부터 다시 읽는다.
     */
    @Test
    @DisplayName("🔴 모르는 작성 스키마면 건너뛰지 않고 멈춘다 — 새 파드가 이어받아 읽도록")
    void anUnknownWriterSchemaHalts() throws Exception {
        start(true);
        byte[] future = unknownSchemaRecord();

        assertThatThrownBy(() -> orderRaw().pipeInput("1", future)).isInstanceOf(StreamsException.class);
        assertThat(counted("halted")).isEqualTo(1);
        assertThat(counted("skipped")).isZero();
    }

    /** 예전 동작(처리기 없음 = LogAndFail) — 깨진 레코드 하나에 멈췄다. 재시작해도 같은 오프셋에서 또 멈춘다. */
    @Test
    @DisplayName("처리기가 없으면 깨진 레코드 하나에 멈춘다 — 예전 동작")
    void withoutTheHandlerAPoisonRecordHalts() {
        start(false);

        assertThatThrownBy(() -> orderRaw().pipeInput("1", "{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(StreamsException.class);
    }
}
