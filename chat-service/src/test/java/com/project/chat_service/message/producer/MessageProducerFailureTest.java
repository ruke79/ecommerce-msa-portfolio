package com.project.chat_service.message.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.chat_service.dto.MessageDto;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 채팅 메시지 발행 실패는 센다 — 경보 ChatMessagePublishFailed.
 *
 * <p>🐛 예전: {@code send} 의 결과를 보지 않아 브로커 장애 · 너무 큰 레코드의 실패가 흔적 없이 사라졌다.
 *
 * <p>[JA] チャットメッセージの発行失敗を数える。以前は send の結果を見ておらず、失敗が跡形もなく消えた。
 */
@DisplayName("MessageProducer — 발행 실패를 센다")
class MessageProducerFailureTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void register() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void unregister() {
        Metrics.removeRegistry(meters);
    }

    @SuppressWarnings("unchecked")
    private static MessageProducer producer(KafkaTemplate<String, MessageDto> kafka) {
        MessageProducer p = new MessageProducer();
        ReflectionTestUtils.setField(p, "kafkaTemplate", kafka);
        return p;
    }

    private static MessageDto message() {
        return MessageDto.builder().roomId("r1").content("hi").build();
    }

    @Test
    @DisplayName("🔴 브로커가 거절하면(비동기 실패) 센다")
    @SuppressWarnings("unchecked")
    void asyncFailureIsCounted() {
        KafkaTemplate<String, MessageDto> kafka = mock(KafkaTemplate.class);
        when(kafka.send(eq("chat"), anyString(), any(MessageDto.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        producer(kafka).send("chat", message());

        assertThat(meters.counter(MessageProducer.FAILED_METRIC).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동기 실패도 세고 호출자에게 올린다 — STOMP 오류 프레임으로 보낸 사람이 안다")
    @SuppressWarnings("unchecked")
    void syncFailureIsCountedAndRethrown() {
        KafkaTemplate<String, MessageDto> kafka = mock(KafkaTemplate.class);
        when(kafka.send(eq("chat"), anyString(), any(MessageDto.class))).thenThrow(new IllegalStateException("metadata"));

        assertThatThrownBy(() -> producer(kafka).send("chat", message())).isInstanceOf(IllegalStateException.class);
        assertThat(meters.counter(MessageProducer.FAILED_METRIC).count()).isEqualTo(1);
    }
}
