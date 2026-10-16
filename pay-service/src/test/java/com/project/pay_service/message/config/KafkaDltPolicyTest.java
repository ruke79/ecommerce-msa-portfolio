package com.project.pay_service.message.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import com.project.common.kafka.DltRetryPolicy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * pay 의 컨슈머 실패 처리 — 결제 요청(order 토픽)이 일시적인 DB 실패로 7초 만에 DLT 로 가지 않게, 가면 센다(경보 KafkaDeadLetterPublished).
 * order · catalog 와 같은 공통 정책(DltRetryPolicy).
 *
 * <p>🐛 예전: 모든 실패가 1 · 2 · 4초 세 번 뒤 order.DLT, 세지 않아 경보가 울리지 않았다 — 결제 요청이 격리돼도 아무도 몰랐다.
 *
 * <p>[JA] pay のコンシューマ失敗処理 — 決済リクエストが一時的な DB 失敗で 7 秒のうちに DLT へ行かないように、行けば数える。
 */
@DisplayName("pay-service — Kafka 실패 처리(재시도 · DLT 계수)")
class KafkaDltPolicyTest {

    @SuppressWarnings("unchecked")
    private static DefaultErrorHandler handler(SimpleMeterRegistry meters) {
        KafkaTemplate<String, Object> json = mock(KafkaTemplate.class);
        KafkaTemplate<String, byte[]> bytes = mock(KafkaTemplate.class);
        when(json.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(bytes.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        return new KafkaDltConfig().kafkaErrorHandler(json, bytes, meters);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 일시적인 실패(교착 40P01)는 약 3분 백오프 — 영구적인 실패는 기본(짧게)")
    void transientFailuresGetTheLongBackOff() {
        Object tracker = ReflectionTestUtils.getField(handler(new SimpleMeterRegistry()), "failureTracker");
        BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> fn =
                (BiFunction<ConsumerRecord<?, ?>, Exception, BackOff>) ReflectionTestUtils.getField(tracker, "backOffFunction");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order", 0, 0L, "k", "v");

        BackOff transientBackOff = fn.apply(record, new CannotAcquireLockException("deadlock", new SQLException("x", "40P01")));
        assertThat(transientBackOff).isNotNull();
        BackOffExecution ex = transientBackOff.start();
        int tries = 0;
        while (ex.nextBackOff() != BackOffExecution.STOP) {
            tries++;
        }
        assertThat(tries).isEqualTo(10);
        assertThat(fn.apply(record, new IllegalStateException("bug"))).as("영구적인 실패는 기본 백오프(3회)").isNull();
    }

    @Test
    @DisplayName("🔴 DLT 로 보내면 토픽별로 센다 — 결제 요청이 격리되면 경보가 울린다")
    void dltIsCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DefaultErrorHandler handler = handler(meters);

        // 재시도할 수 없는 예외(ClassCastException)는 바로 DLT 로 간다
        handler.handleOne(new ClassCastException("poison"), new ConsumerRecord<>("order", 0, 0L, "k", "v"),
                mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(meters.counter(DltRetryPolicy.DLT_METRIC, "topic", "order").count()).isEqualTo(1);
    }
}
