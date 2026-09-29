package com.project.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * 커밋 직후 릴레이 (docs/56).
 *
 * <p>결제 사가가 폴링 간격 두 번을 기다리던 것(약 12초)을 없애는 장치다. 지켜야 할 것은 셋이다 —
 * <b>커밋된 뒤에만</b> 돈다, <b>몰려도 겹쳐 돌지 않는다</b>, <b>실패해도 멈추지 않는다.</b>
 */
@DisplayName("OutboxRelayTrigger — 커밋 직후 릴레이")
class OutboxRelayTriggerTest {

    private final AtomicInteger runs = new AtomicInteger();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        executor.shutdownNow();
    }

    private OutboxRelayTrigger trigger(Runnable relay) {
        return new OutboxRelayTrigger("test", relay, true, executor);
    }

    /** 실행기에 남은 일이 다 끝날 때까지 기다린다 — 단일 스레드라 뒤에 넣은 일이 끝나면 앞의 것도 끝났다. */
    private void awaitIdle() throws Exception {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("트랜잭션 밖(이미 커밋됨)이면 바로 릴레이한다")
    void outsideTransactionRunsNow() throws Exception {
        trigger(runs::incrementAndGet).afterCommit();

        awaitIdle();
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("🔴 트랜잭션 안이면 커밋 전에는 돌지 않는다 — 행이 아직 보이지 않는다")
    void waitsForCommit() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        trigger(runs::incrementAndGet).afterCommit();

        awaitIdle();
        assertThat(runs).as("커밋 전").hasValue(0);

        TransactionSynchronizationUtils.triggerAfterCommit();
        awaitIdle();
        assertThat(runs).as("커밋 후").hasValue(1);
    }

    @Test
    @DisplayName("롤백되면 돌지 않는다 — 보낼 행이 없다")
    void rollbackDoesNothing() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        trigger(runs::incrementAndGet).afterCommit();

        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_ROLLED_BACK);
        awaitIdle();

        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("🔴 도는 중에 몰려온 요청은 '끝나면 한 번 더' 로 합쳐진다 — 겹쳐 돌지 않는다")
    void coalescesWhileRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        OutboxRelayTrigger trigger = trigger(() -> {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            if (runs.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            concurrent.decrementAndGet();
        });

        trigger.afterCommit();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 50; i++) {
            trigger.afterCommit();       // 첫 릴레이가 도는 동안 커밋 50건
        }
        release.countDown();
        awaitIdle();
        awaitIdle();

        // 첫 번째 + 그 사이 쌓인 것을 가져갈 한 번. 50번이 아니다.
        assertThat(runs).hasValue(2);
        assertThat(maxConcurrent).hasValue(1);
    }

    @Test
    @DisplayName("끝난 뒤에 온 요청은 새로 돈다 — 합쳐지는 것은 도는 중에 온 것뿐이다")
    void runsAgainAfterIdle() throws Exception {
        OutboxRelayTrigger trigger = trigger(runs::incrementAndGet);

        trigger.afterCommit();
        awaitIdle();
        trigger.afterCommit();
        awaitIdle();

        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("🔴 릴레이가 실패해도 다음 요청은 돈다 — 상태가 '도는 중' 에 걸리지 않는다")
    void failureDoesNotWedge() throws Exception {
        OutboxRelayTrigger trigger = trigger(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new IllegalStateException("broker down");
            }
        });

        trigger.afterCommit();
        awaitIdle();
        trigger.afterCommit();
        awaitIdle();

        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("실행기가 닫혔으면 조용히 넘긴다 — 행은 폴러가 가져간다")
    void rejectedIsIgnored() {
        OutboxRelayTrigger trigger = trigger(runs::incrementAndGet);
        executor.shutdown();

        trigger.afterCommit();
        trigger.afterCommit();   // 상태가 IDLE 로 돌아와 있어야 두 번째도 예외 없이 지나간다

        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("꺼져 있으면 커밋 콜백도 걸지 않는다 — 폴러만 남는다")
    void disabledRegistersNothing() {
        TransactionSynchronizationManager.initSynchronization();

        new OutboxRelayTrigger("off", runs::incrementAndGet, false).afterCommit();
        OutboxRelayTrigger.DISABLED.afterCommit();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("close() 는 남은 릴레이를 기다린 뒤 실행기를 닫는다")
    void closeShutsDown() throws Exception {
        OutboxRelayTrigger trigger = trigger(runs::incrementAndGet);
        trigger.afterCommit();

        trigger.close();

        assertThat(executor.isShutdown()).isTrue();
        assertThat(runs).hasValue(1);
    }
}
