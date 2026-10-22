package com.project.order_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.avro.OrderStatusChanged;
import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.scheduler.PaymentOutboxScheduler;
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;
import com.project.order_service.service.OrderStatusChanger;

/**
 * Kafka 가 꺼져 있는 동안 order-status 행은 STARTED 로 남는다 — 다음 폴링 · 복구 뒤에 나간다.
 *
 * <p>🐛 2026-10-22 실습 게임데이(kafka-pause, docs/67 §8): 장애 중 order-status 행 3개가 FAILED 로 굳었다. 브로커가 없으면
 * 메타데이터 대기(max.block.ms)가 끝난 순간 {@code KafkaTemplate.send()} 가 실패한 future 를 돌려주지 않고 Spring 의
 * {@code KafkaException("Send failed")} 을 <b>바로 던진다</b> — 발행기가 apache 의 KafkaException 만 잡아 릴레이가 영구 오류로 봤다.
 * 진짜 KafkaTemplate · 진짜 프로듀서로, 아무도 듣지 않는 포트에 보내 그 길을 그대로 탄다(브로커 · 컨테이너 없음).
 *
 * <p>[JA] Kafka 停止中の order-status 行は STARTED のまま残る。実習で FAILED に固まった — ブローカーがないと send() が Spring の
 *      KafkaException を直接投げ、発行器が apache の方しか捕まえていなかった。本物の KafkaTemplate で閉じたポートに送って再現する。
 */
@DisplayName("order-service — Kafka 가 꺼진 동안 order-status 아웃박스 행")
class OrderStatusBrokerDownTest {

    private DefaultKafkaProducerFactory<String, OrderStatusChanged> factory;

    @AfterEach
    void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private KafkaTemplate<String, OrderStatusChanged> templateToNowhere() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();   // 닫힌 뒤에는 아무도 듣지 않는다
        }
        factory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + port,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 300,
                ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 100),
                new StringSerializer(), (topic, value) -> new byte[0]);   // 메타데이터에서 멈추므로 값은 직렬화되지 않는다
        return new KafkaTemplate<>(factory);
    }

    private static PaymentOutboxEvent orderStatusRow(ObjectMapper mapper) throws Exception {
        String payload = mapper.writeValueAsString(
                new OrderStatusChanger.Payload(42L, 7L, "PENDING", "PAID", "payment", Instant.parse("2026-10-22T00:41:00Z")));
        return new PaymentOutboxEvent("42", "Order", PaymentOutboxHelper.ORDER_STATUS_EVENT, payload, OutboxStatus.STARTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 브로커가 없으면 send() 가 Spring KafkaException 을 던진다 — 발행기는 일시 실패(false), 릴레이는 행을 STARTED 로 둔다(FAILED 아님)")
    void brokerDownLeavesTheRowForTheNextPoll() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OrderStatusKafkaPublisher publisher = new OrderStatusKafkaPublisher(mapper, templateToNowhere());
        ReflectionTestUtils.setField(publisher, "publishTimeoutMs", 2_000L);
        PaymentOutboxEvent row = orderStatusRow(mapper);

        PaymentOutboxHelper helper = mock(PaymentOutboxHelper.class);
        when(helper.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(row));
        OutboxTraceContext trace = mock(OutboxTraceContext.class);
        when(trace.resume(any(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());
        PaymentOutboxScheduler relay = new PaymentOutboxScheduler(helper, mock(PaymentRequestKafkaPublisher.class),
                publisher, trace, mock(ProductUpdateKafkaPublisher.class), mock(CartEmptyKafkaPublisher.class));
        ReflectionTestUtils.setField(relay, "batchSize", 10);

        relay.processOutboxMessage();

        assertThat(row.getOutboxStatus()).as("Kafka 가 돌아오면 다시 보낸다 — FAILED 면 영영 안 나간다").isEqualTo(OutboxStatus.STARTED);
    }
}
