package com.project.catalog_service.message.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결제 확정 차감(product-update)이 일시적인 DB 실패로 몇 초 만에 DLT 로 가지 않게 — 그리고 DLT 로 가면 센다.
 *
 * <p>🐛 예전: 모든 실패가 1 · 2 · 4초 세 번 뒤 DLT 였고 경보도 없었다. 교착 · 풀 고갈 · @Version 충돌 하나로 차감 트랜잭션이 통째로
 * 롤백된 메시지가 DLT 로 가면 재고는 줄지 않은 채 예약이 만료되어 같은 수량이 다시 팔렸다.
 *
 * <p>[JA] 決済確定の在庫減算が一時的な DB 失敗で数秒のうちに DLT へ行かないように — そして DLT へ行ったら数える。
 */
@DisplayName("Kafka 재시도 — 일시적 실패는 오래, DLT 는 센다")
class KafkaDltRetryPolicyTest {

    private static SQLException state(String sqlState) {
        return new SQLException("x", sqlState);
    }

    @Test
    @DisplayName("🔴 교착 · 잠금 대기 · 낙관적 잠금 충돌 · 커넥션 실패는 일시적 — 리스너 예외에 싸여 와도")
    void transientFailures() {
        assertThat(KafkaDltConfig.isTransient(new CannotAcquireLockException("deadlock", state("40P01")))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new ListenerExecutionFailedException("l",
                new BatchListenerFailedException("b", new RuntimeException(state("40P01")), 0)))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new RuntimeException(state("40001")))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new RuntimeException(state("55P03")))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new RuntimeException(state("08006")))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new ObjectOptimisticLockingFailureException(Object.class, 1L))).isTrue();
        assertThat(KafkaDltConfig.isTransient(new CannotCreateTransactionException("pool"))).isTrue();
    }

    @Test
    @DisplayName("영구적인 실패(제약 위반 · 버그)는 그대로 짧게 — 파티션을 오래 막지 않는다")
    void permanentFailures() {
        assertThat(KafkaDltConfig.isTransient(new DataIntegrityViolationException("dup", state("23505")))).isFalse();
        assertThat(KafkaDltConfig.isTransient(new NullPointerException())).isFalse();
        assertThat(KafkaDltConfig.isTransient(new IllegalArgumentException())).isFalse();
    }

    @Test
    @DisplayName("일시적 실패의 백오프는 약 3분 — 한 번 쉬는 시간은 30초를 넘지 않는다(max.poll.interval 5분 안)")
    void transientBackOffShape() {
        BackOffExecution ex = KafkaDltConfig.transientBackOff().start();
        long total = 0;
        int tries = 0;
        for (long next = ex.nextBackOff(); next != BackOffExecution.STOP; next = ex.nextBackOff()) {
            assertThat(next).isLessThanOrEqualTo(30_000L);
            total += next;
            tries++;
        }
        assertThat(tries).isEqualTo(10);
        assertThat(total).isBetween(150_000L, 240_000L);
    }

    @Test
    @DisplayName("🔴 DLT 로 보낼 때마다 토픽별로 센다 — 경보 KafkaDeadLetterPublished 의 지표")
    void dltIsCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<String> recovered = new ArrayList<>();
        var counted = KafkaDltConfig.counting((r, e) -> recovered.add(r.topic()), meters);

        counted.accept(new ConsumerRecord<>("product-update", 0, 0L, "k", "v"), new RuntimeException());
        counted.accept(new ConsumerRecord<>("product-update", 0, 1L, "k", "v"), new RuntimeException());

        assertThat(recovered).containsExactly("product-update", "product-update");
        assertThat(meters.counter(KafkaDltConfig.DLT_METRIC, "topic", "product-update").count()).isEqualTo(2.0);
    }
}
