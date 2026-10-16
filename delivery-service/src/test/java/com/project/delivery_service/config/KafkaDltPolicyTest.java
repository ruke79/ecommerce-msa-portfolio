package com.project.delivery_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import com.project.common.kafka.DltRetryPolicy;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * delivery 의 컨슈머 실패 처리 — 결제 완료가 order-service 의 잠깐 멈춤으로 7초 만에 DLT 로 가지 않게, 가면 센다.
 *
 * <p>🐛 예전: 모든 실패가 1 · 2 · 4초 세 번 뒤 payment.DLT, 세지 않아 경보가 울리지 않았다 — 배송 없이 PAID 로 남은 주문을 아무도 몰랐다.
 *
 * <p>[JA] delivery のコンシューマ失敗処理 — order-service の一時停止で決済完了が 7 秒のうちに DLT へ行かないように、行けば数える。
 */
@DisplayName("delivery-service — Kafka 실패 처리(재시도 · DLT 계수)")
class KafkaDltPolicyTest {

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "/api/order/internal/1/shipping",
            Map.of(), null, StandardCharsets.UTF_8, null);

    @SuppressWarnings("unchecked")
    private static DefaultErrorHandler handler(SimpleMeterRegistry meters) {
        KafkaTemplate<String, Object> json = mock(KafkaTemplate.class);
        KafkaTemplate<String, byte[]> bytes = mock(KafkaTemplate.class);
        when(json.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(bytes.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        return new KafkaDltConfig().kafkaErrorHandler(json, bytes, meters);
    }

    private static FeignException status(int code) {
        return FeignException.errorStatus("OrderServiceClient#shipping", feign.Response.builder().status(code)
                .reason("x").request(REQUEST).headers(Map.of()).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 order-service 에 닿지 못함 · 5xx · 429 · DB 교착은 약 3분 백오프 — 404 · 버그는 기본(짧게)")
    void transientFailuresGetTheLongBackOff() {
        Object tracker = ReflectionTestUtils.getField(handler(new SimpleMeterRegistry()), "failureTracker");
        BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> fn =
                (BiFunction<ConsumerRecord<?, ?>, Exception, BackOff>) ReflectionTestUtils.getField(tracker, "backOffFunction");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment", 0, 0L, "k", "v");

        Exception unreachable = new RetryableException(-1, "connect refused", Request.HttpMethod.GET, (Long) null, REQUEST);
        for (Exception ex : new Exception[] {unreachable, status(503), status(429),
                new BatchListenerFailedException("wrapped", status(502), 0),
                new org.springframework.dao.CannotAcquireLockException("deadlock")}) {
            BackOff backOff = fn.apply(record, ex);
            assertThat(backOff).as(ex.toString()).isNotNull();
            BackOffExecution exec = backOff.start();
            int tries = 0;
            while (exec.nextBackOff() != BackOffExecution.STOP) {
                tries++;
            }
            assertThat(tries).isEqualTo(10);
        }
        assertThat(fn.apply(record, status(404))).as("없는 주문은 기다려도 같다").isNull();
        assertThat(fn.apply(record, new IllegalStateException("bug"))).isNull();
    }

    @Test
    @DisplayName("🔴 DLT 로 보내면 토픽별로 센다 — 결제 완료가 격리되면 경보가 울린다")
    @SuppressWarnings("unchecked")
    void dltIsCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DefaultErrorHandler handler = handler(meters);

        handler.handleOne(new ClassCastException("poison"), new ConsumerRecord<>("payment", 0, 0L, "k", "v"),
                mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(meters.counter(DltRetryPolicy.DLT_METRIC, "topic", "payment").count()).isEqualTo(1);
    }
}
