package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.OutgoingMailRequest;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.OutgoingMailProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.UserService;

/**
 * 가입 확인 메일 아웃박스 릴레이(docs/93 ③ 2단계) — 행에는 언어만, 토큰은 보낼 때 · 보내지 못하면 롤백하고 멈춘다 · 이미 인증했으면 건너뛴다.
 *
 * <p>🐛 그 전: 가입 커밋 뒤 비동기 리스너가 Kafka 에 바로 보내 실패하면 메일이 사라졌다(재발송을 눌러야 했다).
 *
 * <p>[JA] 登録確認メールのリレー — 行には言語だけ、トークンは送るときに · 送れなければロールバックして止まる · 認証済みなら飛ばす。
 */
@DisplayName("가입 확인 메일 아웃박스 릴레이")
class RegistrationMailRelayTest {

    /** 커밋 · 롤백을 기록한다. [JA] コミット · ロールバックを記録する。 */
    static final class RecordingTxManager implements PlatformTransactionManager {
        final List<String> ends = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            ends.add(status.isRollbackOnly() ? "rollback" : "commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            ends.add("rollback");
        }
    }

    OutboxEventRepository outbox;
    UserRepository users;
    UserService userService;
    OutgoingMailProducer mail;
    RecordingTxManager txm;
    RegistrationMailRelay relay;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxEventRepository.class);
        users = mock(UserRepository.class);
        userService = mock(UserService.class);
        mail = mock(OutgoingMailProducer.class);
        txm = new RecordingTxManager();
        relay = new RegistrationMailRelay(outbox, users, userService, mail, new ObjectMapper(), new TransactionTemplate(txm),
                "https://shop.example/", () -> "1");
    }

    private static OutboxEvent row(long userId) {
        return new OutboxEvent(String.valueOf(userId), "User", RegistrationMailRelay.EVENT_TYPE, "{\"locale\":\"ja-JP\"}");
    }

    private static User user(long id, boolean enabled) {
        User u = new User("u" + id, "u" + id + "@example.com", "pw");
        u.setUserId(id);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    @DisplayName("🔴 보낼 때 토큰을 만든다 — 링크에 새 토큰 · 받는 주소는 지금의 이메일 · 언어는 행에서, 성공하면 완료로 표시 · 커밋")
    void sendsWithFreshTokenAndCompletes() {
        OutboxEvent r = row(7);
        when(outbox.claimRegistrationMail(1)).thenReturn(List.of(r)).thenReturn(List.of());
        when(users.findById(7L)).thenReturn(Optional.of(user(7, false)));
        when(userService.regenerateVerificationToken(any())).thenReturn("fresh-token");
        when(mail.publish(any())).thenReturn(true);

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<OutgoingMailRequest> req = ArgumentCaptor.forClass(OutgoingMailRequest.class);
        verify(mail).publish(req.capture());
        assertThat(req.getValue().getParams().get("confirmationUrl"))
                .isEqualTo("https://shop.example/user-service/registrationConfirm?token=fresh-token");
        assertThat(req.getValue().getToEmail()).isEqualTo("u7@example.com");
        assertThat(req.getValue().getLocale()).isEqualTo("ja-JP");
        assertThat(r.getOutboxStatus().name()).isEqualTo("COMPLETED");
        assertThat(txm.ends).startsWith("commit");
    }

    @Test
    @DisplayName("🔴 보내지 못하면 그 트랜잭션을 롤백(토큰도 그대로 · 행은 STARTED)하고 묶음을 멈춘다 — 다음 주기에 다시")
    void publishFailureRollsBackAndStops() {
        OutboxEvent r = row(7);
        when(outbox.claimRegistrationMail(1)).thenReturn(List.of(r));
        when(users.findById(7L)).thenReturn(Optional.of(user(7, false)));
        when(userService.regenerateVerificationToken(any())).thenReturn("t");
        when(mail.publish(any())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();

        assertThat(txm.ends).containsExactly("rollback");
        assertThat(r.getOutboxStatus().name()).as("완료로 표시하지 않는다 — 롤백으로 STARTED 가 남는다").isNotEqualTo("COMPLETED");
        verify(outbox, times(1)).claimRegistrationMail(anyInt());
    }

    @Test
    @DisplayName("그 사이 이미 인증했으면 보내지 않고 완료로 표시한다")
    void alreadyVerifiedIsSkipped() {
        OutboxEvent r = row(7);
        when(outbox.claimRegistrationMail(1)).thenReturn(List.of(r)).thenReturn(List.of());
        when(users.findById(7L)).thenReturn(Optional.of(user(7, true)));

        relay.relayOnce();

        verify(mail, never()).publish(any());
        verify(userService, never()).regenerateVerificationToken(any());
        assertThat(r.getOutboxStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("🔴 아웃박스 행에는 언어만 — 원문 토큰 · 이메일을 싣지 않는다")
    void rowCarriesNoTokenOrEmail() throws Exception {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        UserOutboxHelper helper = new UserOutboxHelper(repo, new ObjectMapper(),
                com.project.common.outbox.OutboxTraceContext.NOOP,
                com.project.common.outbox.OutboxRelayTrigger.DISABLED, RegistrationMailTrigger.DISABLED);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            helper.recordRegistrationMail(user(7, false), Locale.JAPAN);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo(RegistrationMailRelay.EVENT_TYPE);
        assertThat(new ObjectMapper().readTree(saved.getValue().getPayload()).fieldNames())
                .toIterable().containsExactly("locale");
        assertThat(saved.getValue().getPayload()).doesNotContain("@").doesNotContain("token");
    }
}
