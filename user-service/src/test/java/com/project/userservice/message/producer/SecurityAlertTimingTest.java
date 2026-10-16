package com.project.userservice.message.producer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 보안 알림 — 커밋 뒤에 보내고, 실패를 남긴다.
 *
 * <p>🐛 예전: 트랜잭션 안에서 바로 보내 롤백돼도 "2FA 꺼짐" 이 나갔고, 결과를 보지 않아 브로커가 끊겨도 "발행" 이 남았다.
 *
 * <p>[JA] セキュリティ通知 — コミット後に送り、失敗を残す。
 */
@DisplayName("보안 알림 — 커밋 뒤 · 결과 확인")
class SecurityAlertTimingTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final SecurityAlertProducer producer = new SecurityAlertProducer(template);

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("🔴 트랜잭션 안에서는 보내지 않고 커밋 뒤에 보낸다 — 롤백되면 보내지 않는다")
    void sendsOnlyAfterCommit() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<SendResult<String, String>>());
        TransactionSynchronizationManager.initSynchronization();

        producer.publishTwoFactorChanged(7L, "a@example.com", false);
        verify(template, never()).send(anyString(), anyString(), anyString());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(template, never()).send(anyString(), anyString(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(template).send(eq(SecurityAlertProducer.TOPIC), eq("7"), any());
    }

    @Test
    @DisplayName("발행 실패는 예외로 새지 않고 ERROR 로 남는다 — 로그인 · 설정 변경은 이미 끝났다")
    void failureIsLoggedNotThrown() {
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatCode(() -> producer.publishAccountEvent(7L, "a@example.com", SecurityAlertProducer.ALERT_PASSWORD_RESET, null))
                .doesNotThrowAnyException();
        verify(template).send(eq(SecurityAlertProducer.TOPIC), eq("7"), any());
    }
}
