package com.project.chat_service.common.config.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 🔴 채팅 · 상담 알림 프로듀서의 막힘 상한 — send() 는 STOMP 핸들러 스레드에서 불린다.
 *
 * <p>🐛 예전: 기본 max.block.ms(60초). 브로커가 흔들리면 clientInboundChannel 의 스레드가 메시지마다 60초씩 막혀 CONNECT ·
 * SUBSCRIBE 까지 멈췄다(pay PY6 과 같은 결함).
 *
 * <p>[JA] 以前は既定の 60 秒。ブローカー不調時に STOMP 受信スレッドがメッセージごとに 60 秒塞がった。
 */
@DisplayName("chat — 프로듀서 막힘 상한")
class KafkaProducerTimeoutTest {

    private static java.util.Map<String, Object> config() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "environment", new MockEnvironment());
        ReflectionTestUtils.setField(config, "kafkaServerUrl", "localhost:1");
        return config.producerConfig();
    }

    @Test
    @DisplayName("🔴 max.block.ms = 5초 — 채팅 · 상담 알림 템플릿이 같은 맵을 쓴다")
    void maxBlockIsBounded() {
        assertThat(config().get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("acks=all · 멱등 — 다른 모듈처럼 적는다")
    void acksAll() {
        assertThat(config())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    }
}
