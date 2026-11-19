package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.kafka.avro.AvroDtoSerializer;
import com.project.common.kafka.avro.UserCreatedRequestAvroMapping;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.common.outbox.OutboxTraceContext;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.producer.UserLifecycleProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;

/**
 * 아웃박스 폴러 × 실제 Kafka(cp-kafka 7.6.1 — 실습 · 운영과 같은 판) — 묶음을 모두 보내고 함께 기다린다(docs/79 §4).
 *
 * <p>브로커를 멈추면(pause — 연결은 살아 있고 ack 만 오지 않는다) 묶음 100건이 기한 한 번에 끝나는지 본다. 예전(한 건씩 기다림)은
 * 100 × 기한이었다. 저장소는 가짜(선점 결과만) — 트랜잭션 길이 = 이 메서드가 돌아오는 시간.
 *
 * <p>[JA] アウトボックスのポーラー × 実 Kafka。ブローカーを一時停止すると、バッチ 100 件が期限 1 回で終わるかを見る。以前は 100 × 期限だった。
 */
@Tag("containers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("아웃박스 폴러 × 실제 Kafka — 묶음 발행 · 브로커 정지 때의 트랜잭션 길이")
class UserOutboxRelayKafkaTest {

    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
            DockerImageName.parse("mirror.gcr.io/confluentinc/cp-kafka:7.6.1").asCompatibleSubstituteFor("confluentinc/cp-kafka"));

    static UserOutboxScheduler scheduler;
    static final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @BeforeAll
    static void start() {
        KAFKA.start();
        Map<String, Object> base = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        UserLifecycleProducer lifecycle = new UserLifecycleProducer(base,
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.observation.ObservationRegistry.class));
        // 운영과 같은 직렬화(Avro 단일 객체 인코딩 — KafkaProducerConfig#userCreatedProducerFactory)
        KafkaTemplate<String, UserCreatedRequest> createdTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(base,
                new StringSerializer(), new AvroDtoSerializer<>(new UserCreatedRequestAvroMapping())));
        scheduler = new UserOutboxScheduler(repository, new UserCreatedProducer(createdTemplate), lifecycle,
                new ObjectMapper(), OutboxTraceContext.NOOP);
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private static List<OutboxEvent> signupsAndUpdates(int users, long firstId) {
        List<OutboxEvent> batch = new ArrayList<>();
        for (long id = firstId; id < firstId + users; id++) {
            batch.add(new OutboxEvent(String.valueOf(id), "User", UserCreatedProducer.TOPIC,
                    "{\"userId\":" + id + ",\"username\":\"demo" + id + "\",\"nickname\":\"n" + id + "\"}"));
        }
        for (long id = firstId; id < firstId + users; id++) {
            batch.add(new OutboxEvent(String.valueOf(id), "User", UserLifecycleProducer.UPDATED, "{\"userId\":" + id + "}"));
        }
        return batch;
    }

    private static long count(String topic) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "count-" + topic + "-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of(topic));
            long n = 0;
            long until = System.currentTimeMillis() + 15_000;
            int idle = 0;
            while (System.currentTimeMillis() < until && idle < 3) {
                int got = c.poll(Duration.ofSeconds(1)).count();
                n += got;
                idle = got == 0 && n > 0 ? idle + 1 : 0;
            }
            return n;
        }
    }

    @Test
    @Order(1)
    @DisplayName("🔴 묶음 200건(가입 100 · 같은 사용자의 변경 100)이 모두 나가고 COMPLETED — 토픽마다 100건")
    void wholeBatchIsDelivered() {
        List<OutboxEvent> batch = signupsAndUpdates(100, 7_000_000_000L);
        when(repository.claimPending(any(Integer.class))).thenReturn(batch);
        // 운영 기본 기한(10초) — 새 브로커의 첫 발행은 토픽 자동 생성 · 메타데이터 받기로 1초를 넘는다
        scheduler.setPublishTimeoutMs(10_000);

        scheduler.processOutboxMessage();

        assertThat(batch).extracting(e -> e.getOutboxStatus().name()).containsOnly("COMPLETED");
        assertThat(count(UserCreatedProducer.TOPIC)).isEqualTo(100);
        assertThat(count(UserLifecycleProducer.UPDATED)).isEqualTo(100);
    }

    @Test
    @Order(2)
    @DisplayName("🔴 브로커가 멈추면 묶음 100건(가입 50 · 변경 50)이 기한(1초) 한 번에 FAILED — 예전의 100 × 1초가 아니다")
    void pausedBrokerCostsOneDeadline() {
        List<OutboxEvent> batch = signupsAndUpdates(50, 7_000_001_000L);
        when(repository.claimPending(any(Integer.class))).thenReturn(batch);
        scheduler.setPublishTimeoutMs(1_000);   // 메타데이터는 앞 시험이 받아 두었다 — 보내기는 막히지 않고 ack 만 오지 않는다
        String id = KAFKA.getContainerId();
        KAFKA.getDockerClient().pauseContainerCmd(id).exec();
        try {
            long t0 = System.nanoTime();
            scheduler.processOutboxMessage();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            // 같은 사용자의 변경은 가입의 ack 를 기다린다 — 그 기다림도 같은 기한 안이다(묶음 전체의 기한은 하나)
            assertThat(ms).as("트랜잭션 길이 ≈ 기한 1초 한 번").isLessThan(5_000);
            assertThat(batch).extracting(e -> e.getOutboxStatus().name()).containsOnly("FAILED");
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(id).exec();
        }
    }
}
