package com.project.delivery_service.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.outbox.DeliveryOutbox;

/**
 * 배송 상태 발행 — 상태를 바꾼 트랜잭션 안에서 아웃박스에 적고, 커밋 뒤에 릴레이를 깨운다(docs/93 ③ 2단계).
 *
 * <p>🐛 그 전: 커밋 뒤에 직접 Kafka 로 보내고 실패는 세기만 해, 실패한 이벤트는 사라졌다(주문 상태 · 알림이 멈췄다). 그보다 전에는
 * 트랜잭션 안에서 바로 보내 롤백된 상태가 나갔다. 롤백되면 행이 없다는 것은 {@code DeliveryOutboxPostgresTest} 가 실제 DB 로 본다.
 *
 * <p>[JA] 配送状態の発行 — 状態を変えたトランザクション内でアウトボックスに書き、コミット後にリレーを起こす。
 */
@DisplayName("배송 상태 발행 — 아웃박스에 적고 커밋 뒤 릴레이를 깨운다")
class DeliveryStatusPublisherCommitTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static DeliveryStatusPublisher publisher(DeliveryOutbox outbox, OutboxRelayTrigger trigger) {
        DeliveryStatusPublisher p = new DeliveryStatusPublisher(outbox, trigger, new ObjectMapper());
        ReflectionTestUtils.setField(p, "topic", "delivery-status");
        return p;
    }

    private static Delivery delivery() {
        return Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
    }

    /** 실행기 없이 깨운 횟수만 센다. [JA] 起こした回数だけ数える。 */
    private static final class CountingTrigger extends OutboxRelayTrigger {
        final AtomicInteger kicks = new AtomicInteger();

        CountingTrigger() {
            super("test", () -> { }, false);
        }

        @Override
        public void afterCommit() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        kicks.incrementAndGet();
                    }
                });
            } else {
                kicks.incrementAndGet();
            }
        }
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서 아웃박스에 적는다(주문 id 가 키 · eventId 포함) — 릴레이는 커밋 뒤에만 깨운다")
    void enqueuesInTransactionAndKicksAfterCommit() throws Exception {
        DeliveryOutbox outbox = mock(DeliveryOutbox.class);
        CountingTrigger trigger = new CountingTrigger();
        TransactionSynchronizationManager.initSynchronization();

        publisher(outbox, trigger).publish(delivery(), DeliveryStatus.DELIVERED);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("delivery-status"), eq("1"), payload.capture());
        assertThat(new ObjectMapper().readTree(payload.getValue()).path("eventId").asText()).isEqualTo("delivery:1:DELIVERED");
        assertThat(trigger.kicks.get()).as("커밋 전에는 깨우지 않는다").isZero();

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(trigger.kicks.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 아웃박스에 적지 못하면 예외가 올라간다 — 상태만 바뀌고 이벤트가 없는 일을 만들지 않는다(상태 변경도 롤백)")
    void enqueueFailurePropagates() {
        DeliveryOutbox outbox = mock(DeliveryOutbox.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(outbox).enqueue(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> publisher(outbox, new CountingTrigger()).publish(delivery(), DeliveryStatus.DELIVERED))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
