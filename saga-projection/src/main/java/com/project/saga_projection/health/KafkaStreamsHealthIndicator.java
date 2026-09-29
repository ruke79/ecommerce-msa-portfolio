package com.project.saga_projection.health;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * Streams 클라이언트 상태를 헬스로 낸다 — Boot 는 Streams 용 헬스를 자동 구성하지 않는다.
 *
 * <p>readiness 그룹에 넣는다(application-local.yml). 처리 스레드가 죽어 클라이언트가 내려가면 JVM 은
 * 살아 있어 파드는 Running 이지만, 이 지표가 DOWN 이라 트래픽에서 빠지고 대시보드에 드러난다.
 * REBALANCING 은 UP 이다 — 파드가 늘거나 줄 때마다 잠깐 거치는 정상 상태라 그때마다 트래픽을 빼면
 * 배포 중 조회가 전부 503 이 된다.
 */
@Component("kafkaStreams")
public class KafkaStreamsHealthIndicator implements HealthIndicator {

    private final StreamsBuilderFactoryBean factory;

    public KafkaStreamsHealthIndicator(StreamsBuilderFactoryBean factory) {
        this.factory = factory;
    }

    @Override
    public Health health() {
        KafkaStreams streams = factory.getKafkaStreams();
        if (streams == null) {
            return Health.outOfService().withDetail("state", "NOT_STARTED").build();
        }
        KafkaStreams.State state = streams.state();
        Health.Builder b = switch (state) {
            case RUNNING, REBALANCING -> Health.up();
            case CREATED -> Health.outOfService();
            default -> Health.down();
        };
        return b.withDetail("state", state.name()).build();
    }
}
