package com.project.saga_projection.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;

import com.project.common.config.KafkaSecuritySettings;
import com.project.saga_projection.topology.SagaTopology;

import lombok.extern.slf4j.Slf4j;

/**
 * Kafka Streams 설정 — 이 저장소에서 유일하게 <b>exactly-once 가 실제로 성립하는</b> 소비자(docs/38 §1.17).
 *
 * <p>{@code processing.guarantee=exactly_once_v2}: 입력 오프셋 커밋, 상태 저장소 변경 로그, 리파티션 토픽,
 * 출력 토픽이 <b>한 Kafka 트랜잭션</b>이다. 처리 중 죽으면 전부 중단되고 재시작한 인스턴스가 같은 입력을
 * 다시 접는다 — 같은 결과로. {@code transactional.id} 는 Streams 가 application.id + 태스크로 스스로 고정
 * 하므로 §1.17 의 "기동마다 UUID" 문제가 여기엔 없다. 소비자는 {@code isolation.level=read_committed} 가
 * 자동이다.
 *
 * <p>이 보장은 <b>Kafka→Kafka 이기 때문에</b> 가능하다. DB 를 갱신하는 리스너는 DB 커밋과 Kafka 커밋이
 * 원자적일 수 없어 EOS 가 아니라 멱등 원장을 쓴다 — 그래서 Streams 를 기존 리스너에 끼우지 않고 새
 * 읽기 모델에 썼다.
 */
@Slf4j
@Configuration
@EnableKafkaStreams
@EnableConfigurationProperties(SagaProjectionProperties.class)
public class KafkaStreamsConfig {

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration defaultKafkaStreamsConfig(Environment env, SagaProjectionProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, props.applicationId());
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, env.getRequiredProperty("kafka.url"));
        config.put(StreamsConfig.APPLICATION_SERVER_CONFIG, props.applicationServer());
        config.put(StreamsConfig.STATE_DIR_CONFIG, props.stateDir());
        config.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, props.standbyReplicas());

        config.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        // EOS 의 커밋 간격 기본값은 100ms 다. 트랜잭션 하나 = 커밋 하나이므로 너무 짧으면 브로커에
        // 트랜잭션 마커만 쌓이고, 너무 길면 read_committed 소비자(다른 서비스가 order-saga-state 를
        // 읽을 때)가 그만큼 늦게 본다. 1초면 사가 조회에 충분하다.
        config.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1_000);

        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        // 값 serde 는 토폴로지가 단계마다 명시한다 — 기본값이 있으면 빠뜨린 자리가 조용히 그 기본값으로
        // 돌아가므로 일부러 두지 않는다(빠뜨리면 기동에서 죽는다, 실행 중이 아니라).

        // 처음 붙을 때 토픽의 처음부터 접는다 — 프로젝션은 과거 주문도 알아야 한다.
        config.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        // 내부 토픽(변경 로그·리파티션)의 복제 계수. -1 = 브로커 기본값. 브로커가 하나인 dev 에서 3 을
        // 고정하면 내부 토픽 생성이 실패해 기동이 멈춘다.
        config.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, -1);
        config.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 1);
        config.putAll(errorHandling());

        config.putAll(KafkaSecuritySettings.from(env));
        return new KafkaStreamsConfiguration(config);
    }

    /**
     * 입력 레코드를 못 읽었을 때의 처리 — 모르는 작성 스키마면 멈추고, 그 밖의 깨진 레코드는 건너뛴다
     * ({@link SagaDeserializationExceptionHandler}). 예전에는 기본값(LogAndFail)이라 깨진 레코드 하나에 프로젝션 전체가
     * 멈췄다. 테스트(TopologyTestDriver)도 이 설정을 그대로 쓴다.
     */
    public static Map<String, Object> errorHandling() {
        return Map.of(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                SagaDeserializationExceptionHandler.class);
    }

    /** 토폴로지를 {@link StreamsBuilder} 에 붙인다. 테스트는 같은 {@link SagaTopology} 를 TopologyTestDriver 에 붙인다. */
    @Bean
    public SagaTopology sagaTopology(SagaProjectionProperties props) {
        return new SagaTopology(props.topics().order(), props.topics().payment(), props.topics().state(),
                props.topics().orderStatus(), props.topics().timeline());
    }

    @Bean
    public Topology sagaStreamsTopology(StreamsBuilder builder, SagaTopology topology) {
        topology.build(builder);
        Topology built = builder.build();
        log.info("saga-projection topology:\n{}", built.describe());
        return built;
    }

    /**
     * 처리 스레드가 죽었을 때. 기본값(SHUTDOWN_CLIENT)은 Streams 클라이언트만 멈추고 JVM 은 살아 있다 —
     * 파드는 Running 인데 아무것도 접지 않는, 가장 나쁜 실패 방식이다. 상태를 헬스가 보고 readiness 를
     * 내리므로(KafkaStreamsHealthIndicator) 트래픽은 빠지지만, 복구는 재시작뿐이라 여기서 로그를 남긴다.
     * 스레드 교체(REPLACE_THREAD)는 역직렬화 실패 같은 결정적 오류에서 무한 반복이 되므로 쓰지 않는다.
     */
    @Bean
    public StreamsBuilderFactoryBeanConfigurer sagaStreamsConfigurer() {
        return (StreamsBuilderFactoryBean fb) -> {
            fb.setStreamsUncaughtExceptionHandler(e -> {
                log.error("saga-projection 처리 스레드가 죽었다 — 클라이언트를 내린다. readiness 가 DOWN 으로 바뀐다", e);
                return org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
            });
            fb.setStateListener((newState, oldState) -> log.info("saga-projection streams {} → {}", oldState, newState));
        };
    }
}
