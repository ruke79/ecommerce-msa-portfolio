package com.project.pay_service.message.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 🔴 PY6 — 결제 응답 프로듀서의 max.block.ms 는 발행 기다림 상한(기본 5초)과 같다. 예전에는 기본 60초라, 브로커 메타데이터를 기다리는 동안
 * send() 가 60초 막혀 "5초 = 릴레이의 행 잠금 보유 상한" 이 지켜지지 않았다.
 */
@DisplayName("pay-service — 결제 응답 프로듀서의 막힘 상한")
class KafkaProducerTimeoutTest {

    @Test
    @DisplayName("🔴 max.block.ms = 발행 기다림 상한")
    void maxBlockFollowsThePublishTimeout() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:1");

        assertThat(config.producerConfig().get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5000L);
    }
}
