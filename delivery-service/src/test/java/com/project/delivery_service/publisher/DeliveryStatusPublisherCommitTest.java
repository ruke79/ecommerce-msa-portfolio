package com.project.delivery_service.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 배송 상태 발행 — 커밋 뒤에 보내고, 실패를 센다.
 *
 * <p>🐛 예전: 트랜잭션 안에서 바로 보내 롤백된 상태가 나갔고, send 의 결과를 보지 않아 비동기 실패는 로그조차 없었다.
 *
 * <p>[JA] 配送状態の発行 — コミットの後に送り、失敗を数える。
 */
@DisplayName("배송 상태 발행 — 커밋 뒤 · 실패 계수")
class DeliveryStatusPublisherCommitTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        Metrics.removeRegistry(meters);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka(CompletableFuture<SendResult<String, String>> result) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(result);
        return kafka;
    }

    private static DeliveryStatusPublisher publisher(KafkaTemplate<String, String> kafka) {
        DeliveryStatusPublisher p = new DeliveryStatusPublisher(kafka, new ObjectMapper());
        ReflectionTestUtils.setField(p, "topic", "delivery-status");
        return p;
    }

    private static Delivery delivery() {
        return Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서는 커밋 뒤에 보낸다 — 롤백되면 나가지 않는다")
    void sendsAfterCommit() {
        KafkaTemplate<String, String> kafka = kafka(CompletableFuture.completedFuture(null));
        TransactionSynchronizationManager.initSynchronization();

        publisher(kafka).publish(delivery(), DeliveryStatus.DELIVERED);
        verify(kafka, never()).send(anyString(), anyString(), anyString());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(kafka).send(eq("delivery-status"), eq("1"), anyString());
    }

    @Test
    @DisplayName("🔴 브로커가 거절하면(비동기 실패) 센다 — 경보 DeliveryStatusPublishFailed")
    void asyncFailureIsCounted() {
        Metrics.addRegistry(meters);

        publisher(kafka(CompletableFuture.failedFuture(new RuntimeException("broker down"))))
                .publish(delivery(), DeliveryStatus.DELIVERED);

        assertThat(meters.counter(DeliveryStatusPublisher.FAILED_METRIC).count()).isEqualTo(1);
    }
}
