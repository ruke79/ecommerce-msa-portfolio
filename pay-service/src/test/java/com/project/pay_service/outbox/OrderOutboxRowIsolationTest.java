package com.project.pay_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.outbox.scheduler.OrderOutboxScheduler;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결제 응답 릴레이 — 행 하나의 영구 오류는 그 행만 FAILED 로, 뒤의 결제 응답은 계속 나간다(PY6, order 와 같다).
 *
 * <p>🐛 예전: 깨진 행 하나의 예외가 배치 트랜잭션째 롤백시켜 같은 행을 매 폴링 맨 앞에서 다시 집었다 — 그 뒤의 결제 응답이 전부 멈췄다.
 */
@DisplayName("pay-service — 결제 응답 릴레이의 행 격리")
class OrderOutboxRowIsolationTest {

    @Test
    @DisplayName("🔴 보낼 수 없는 행은 FAILED · 계수, 뒤의 행은 그대로 보낸다")
    void brokenRowIsIsolated() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        try {
            OrderOutboxHelper helper = mock(OrderOutboxHelper.class);
            PaymentResponseKafkaPublisher publisher = mock(PaymentResponseKafkaPublisher.class);
            OrderOutboxEvent broken = new OrderOutboxEvent("1", "Payment", "payment-response", "{broken", OutboxStatus.STARTED);
            OrderOutboxEvent good = new OrderOutboxEvent("2", "Payment", "payment-response", "{}", OutboxStatus.STARTED);
            when(helper.claimOrderOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(broken, good));
            when(publisher.publish(eq(broken), any())).thenThrow(new IllegalStateException("payload unreadable"));
            when(publisher.publish(eq(good), any())).thenReturn(true);

            OrderOutboxScheduler scheduler = new OrderOutboxScheduler(helper, publisher, OutboxTraceContext.NOOP);
            ReflectionTestUtils.setField(scheduler, "batchSize", 100);
            scheduler.processOutboxMessage();

            assertThat(broken.getOutboxStatus()).isEqualTo(OutboxStatus.FAILED);
            verify(helper).save(broken);
            verify(publisher).publish(eq(good), any());
            assertThat(meters.counter(OrderOutboxScheduler.FAILED_METRIC).count()).isEqualTo(1);
        } finally {
            Metrics.removeRegistry(meters);
        }
    }
}
