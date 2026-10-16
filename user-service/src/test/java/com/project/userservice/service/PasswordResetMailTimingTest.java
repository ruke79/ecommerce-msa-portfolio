package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.project.userservice.message.producer.OutgoingMailProducer;

/**
 * 비밀번호 재설정 메일 — 트랜잭션 안에서 Kafka 를 기다리지 않는다.
 *
 * <p>🐛 예전: DB 트랜잭션 안에서 발행을 동기로 기다렸다(최대 5초) — 커넥션 풀이 바닥나고, 가입된 주소만 그 시간이 걸려 응답 시간으로
 * 가입 여부가 갈렸다.
 *
 * <p>[JA] パスワード再設定メール — トランザクション内で Kafka を待たない。
 */
@DisplayName("비밀번호 재설정 메일 — 커밋 뒤 · 요청 스레드 밖")
class PasswordResetMailTimingTest {

    private final OutgoingMailProducer producer = mock(OutgoingMailProducer.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final EmailService email = new EmailService(producer, queued::add);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서는 발행하지 않고, 커밋 뒤 메일 일꾼에 넘긴다")
    void publishesAfterCommitOnTheMailWorker() {
        when(producer.publish(any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        email.sendPasswordResetEmail(7L, "kim@example.com", "https://shop/reset?token=t");

        verify(producer, never()).publish(any());
        assertThat(queued).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(queued).hasSize(1);
        verify(producer, never()).publish(any());   // 요청 스레드에서는 여전히 아니다

        queued.get(0).run();
        verify(producer).publish(any());
    }

    @Test
    @DisplayName("롤백되면 메일을 보내지 않는다 — 사라질 토큰의 링크")
    void rollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        email.sendPasswordResetEmail(7L, "kim@example.com", "https://shop/reset?token=t");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(queued).isEmpty();
        verify(producer, never()).publish(any());
    }
}
