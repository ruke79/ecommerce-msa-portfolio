package com.project.common.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ops/56-dlt-replay.sh — 실습 · 운영과 같은 cp-kafka 7.6.1 파드 안에서 도구가 돈다(docker exec 가 kubectl exec 자리).
 *
 * <p>2026-10-22 실습 게임데이(poison-message): 콘솔 도구는 값을 줄 단위 글자로 다뤄 Avro 값이 깨졌다 — 그래서 값에 줄바꿈 ·
 * 0 · 0xFF 바이트를 넣고 바이트 그대로 돌아오는지 본다. 원래 파티션(프로듀서는 키 접두사를 벗겨 파티션을 정한다) · 헤더(DLT 헤더만
 * 뺀다) · 보이기만(APPLY 없으면 보내지 않는다) · 값을 출력하지 않는다(개인정보)를 함께 본다.
 *
 * <p>[JA] ops/56 — 実習・本番と同じ cp-kafka 7.6.1 内で動く。値に改行・0・0xFF を入れ、バイトのまま戻るか、元のパーティション・
 *      ヘッダー・ドライラン・値を出力しないことを確かめる。
 */
@Tag("containers")
@DisplayName("ops/56 — DLT 재처리(cp-kafka 7.6.1 안의 단일 파일 Java)")
class DltReplayScriptTest {

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
            DockerImageName.parse("mirror.gcr.io/confluentinc/cp-kafka:7.6.1").asCompatibleSubstituteFor("confluentinc/cp-kafka"));

    private record Run(int code, String out) {}

    @BeforeAll
    static void start() {
        KAFKA.start();
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        return Files.exists(p.resolve("ops")) ? p : p.getParent();
    }

    private static Run script(Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("bash", repoRoot().resolve("ops/56-dlt-replay.sh").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoRoot().toFile()).redirectErrorStream(true);
        pb.environment().put("KAFKA_EXEC", "docker exec -i " + KAFKA.getContainerId());
        pb.environment().put("BOOTSTRAP", "localhost:9093");   // 컨테이너 안에서 본 브로커(BROKER 리스너)
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(3, TimeUnit.MINUTES)).as(out).isTrue();
        return new Run(p.exitValue(), out);
    }

    private static Map<String, Object> clientProps() {
        Map<String, Object> m = new HashMap<>();
        m.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        return m;
    }

    /** 토픽 둘(원래 3 파티션 · DLT 1 파티션) — 테스트마다 새 이름. */
    private static String topics() throws Exception {
        String base = "gd-" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = Admin.create(clientProps())) {
            admin.createTopics(List.of(new NewTopic(base, 3, (short) 1), new NewTopic(base + ".DLT", 1, (short) 1))).all().get();
        }
        return base;
    }

    /** Spring DeadLetterPublishingRecoverer 와 같은 모양의 헤더 — 원래 파티션 · 오프셋은 4 · 8 바이트 정수. */
    private static void dlt(String topic, byte[] key, byte[] value, boolean withOriginalTopic) throws Exception {
        RecordHeaders h = new RecordHeaders();
        if (withOriginalTopic) {
            h.add("kafka_dlt-original-topic", topic.getBytes(StandardCharsets.UTF_8));
        }
        h.add("kafka_dlt-original-partition", ByteBuffer.allocate(4).putInt(2).array());
        h.add("kafka_dlt-original-offset", ByteBuffer.allocate(8).putLong(7).array());
        h.add("kafka_dlt-original-consumer-group", "payment-request".getBytes(StandardCharsets.UTF_8));
        h.add("kafka_dlt-exception-fqcn", "org.springframework.kafka.support.serializer.DeserializationException".getBytes(StandardCharsets.UTF_8));
        h.add("kafka_dlt-exception-message", "failed to deserialize; Unknown magic byte!".getBytes(StandardCharsets.UTF_8));
        h.add("kafka_dlt-exception-cause-fqcn", "feign.RetryableException".getBytes(StandardCharsets.UTF_8));
        h.add("kafka_dlt-exception-stacktrace", ("org.springframework.kafka.listener.ListenerExecutionFailedException: Listener method threw\n"
                + "\tat x.y.Z.m(Z.java:1)\n"
                + "Caused by: org.springframework.kafka.listener.BatchListenerFailedException: order 알림 처리 실패\n"
                + "\t... 3 more\n"
                + "Caused by: feign.RetryableException: connect timed out executing GET https://user-service/api/user?email=buyer@example.com\n")
                .getBytes(StandardCharsets.UTF_8));
        h.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> props = clientProps();
        try (KafkaProducer<byte[], byte[]> p = new KafkaProducer<>(props, new ByteArraySerializer(), new ByteArraySerializer())) {
            p.send(new ProducerRecord<>(topic + ".DLT", 0, key, value, h)).get();
        }
    }

    private static List<ConsumerRecord<byte[], byte[]>> readAll(String topic, int partitions) {
        Map<String, Object> props = clientProps();
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<byte[], byte[]>> got = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> tps = new ArrayList<>();
            for (int i = 0; i < partitions; i++) {
                tps.add(new TopicPartition(topic, i));
            }
            c.assign(tps);
            c.seekToBeginning(tps);
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(300)).forEach(got::add);
            }
        }
        return got;
    }

    /** 줄바꿈 · 0 · 0xFF · CR 이 든 값 — 콘솔 도구로 옮기면 깨지는 모양. 개인정보 자리 표시도 넣어 출력되지 않는지 본다. */
    private static byte[] binaryValue() {
        byte[] marker = "ADDR-SEOUL-123".getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(marker.length + 8);
        b.put(new byte[] { 0x00, 0x0A, (byte) 0xFF, 0x0D }).put(marker).put(new byte[] { 0x0A, 0x0A, 0x00, 0x7F });
        return b.array();
    }

    @Test
    @DisplayName("🔴 APPLY=1 — 값 · 키를 바이트 그대로, 원래 파티션(2)으로, DLT 헤더만 빼고 dlt-replayed-from 을 더해 원래 토픽에")
    void replaysBytesToTheOriginalPartition() throws Exception {
        String t = topics();
        byte[] value = binaryValue();
        dlt(t, "order-42".getBytes(StandardCharsets.UTF_8), value, true);

        Run r = script(Map.of("APPLY", "1"), "replay", t + ".DLT", "0", "0");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("보냄").contains("다시 넣음: 1건").doesNotContain("ADDR-SEOUL");
        List<ConsumerRecord<byte[], byte[]>> got = readAll(t, 3);
        assertThat(got).as("원래 토픽에 한 건").hasSize(1);
        ConsumerRecord<byte[], byte[]> back = got.get(0);
        assertThat(back.partition()).as("원래 파티션 — 키 해시로 보내면 같은 주문의 순서가 어긋난다").isEqualTo(2);
        assertThat(back.value()).as("값은 바이트 그대로").isEqualTo(value);
        assertThat(new String(back.key(), StandardCharsets.UTF_8)).isEqualTo("order-42");
        List<String> names = new ArrayList<>();
        for (Header h : back.headers()) {
            names.add(h.key());
        }
        assertThat(names).as("DLT 헤더는 빼고, 나머지(추적 문맥)는 그대로").contains("traceparent", "dlt-replayed-from")
                .noneMatch(n -> n.startsWith("kafka_dlt-"));
        assertThat(new String(back.headers().lastHeader("dlt-replayed-from").value(), StandardCharsets.UTF_8))
                .isEqualTo(t + ".DLT:0:0");
    }

    @Test
    @DisplayName("🔴 APPLY 없으면 보이기만 — 원래 토픽은 비어 있다")
    void dryRunSendsNothing() throws Exception {
        String t = topics();
        dlt(t, "order-1".getBytes(StandardCharsets.UTF_8), binaryValue(), true);

        Run r = script(Map.of(), "replay", t + ".DLT", "0", "0");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("[보내지 않음]").contains(t + ":2").contains("APPLY=1");
        assertThat(readAll(t, 3)).isEmpty();
    }

    @Test
    @DisplayName("list — 자리 · 키 · 크기 · 원래 자리 · 실패한 그룹 · 예외만, 값은 출력하지 않는다(개인정보)")
    void listShowsWhereAndWhyButNotTheValue() throws Exception {
        String t = topics();
        byte[] value = binaryValue();
        dlt(t, "order-7".getBytes(StandardCharsets.UTF_8), value, true);

        Run r = script(Map.of(), "list", t + ".DLT");

        assertThat(r.code()).as(r.out()).isZero();
        assertThat(r.out()).contains("레코드 1건").contains("0:0").contains("키=order-7").contains("값=" + value.length + "바이트")
                .contains("원래=" + t + "-2-7").contains("그룹=payment-request").contains("Unknown magic byte")
                .contains("원인 feign.RetryableException")
                .contains("사슬 org.springframework.kafka.listener.BatchListenerFailedException → feign.RetryableException")
                .doesNotContain("buyer@example.com")
                .doesNotContain("ADDR-SEOUL");
    }

    @Test
    @DisplayName("원래 토픽 헤더가 없으면 보내지 않는다(종료 2) — TO 로 정한다 · 없는 오프셋은 종료 3")
    void refusesWithoutDestinationOrRecord() throws Exception {
        String t = topics();
        dlt(t, "order-9".getBytes(StandardCharsets.UTF_8), binaryValue(), false);

        Run noDest = script(Map.of("APPLY", "1"), "replay", t + ".DLT", "0", "0");
        assertThat(noDest.code()).as(noDest.out()).isEqualTo(2);
        assertThat(noDest.out()).contains("TO=<토픽>");
        assertThat(readAll(t, 3)).isEmpty();

        Run missing = script(Map.of("APPLY", "1"), "replay", t + ".DLT", "0", "5");
        assertThat(missing.code()).as(missing.out()).isEqualTo(3);

        Run withTo = script(Map.of("APPLY", "1", "TO", t), "replay", t + ".DLT", "0", "0");
        assertThat(withTo.code()).as(withTo.out()).isZero();
        assertThat(readAll(t, 3)).hasSize(1);
    }
}
