package com.project.delivery_service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 오프셋이 없을 때 — 처음부터 읽는다.
 *
 * <p>🐛 예전: latest. 그룹이 처음 뜨거나 오프셋이 만료된 사이의 결제 완료가 사라져 결제됐는데 배송이 없는 주문이 남았다.
 * notification 은 같은 결함을 N-M5 로 고쳤는데 이 서비스에는 들어오지 않았다.
 *
 * <p>[JA] オフセットがないとき — 最初から読む。以前は latest で、その間の決済完了が消えていた。
 */
@DisplayName("delivery — 컨슈머 오프셋 기본값")
class DeliveryKafkaOffsetResetTest {

    @Test
    @DisplayName("🔴 결제 완료 컨슈머의 기본은 earliest — 다시 읽어도 멱등이다")
    void defaultsToEarliest() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:9092");

        assertThat(config.deliveryConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }
}
