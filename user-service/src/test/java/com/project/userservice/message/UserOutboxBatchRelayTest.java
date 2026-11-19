package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxTraceContext;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.producer.UserLifecycleProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;

/**
 * 아웃박스 폴러의 묶음 발행 — 모두 보낸 뒤 한 번의 기한 안에서 함께 기다린다(docs/79 §4).
 *
 * <p>예전에는 한 건씩 보내고 ack 를 기다려, 폴러의 트랜잭션(행 잠금 · DB 커넥션)이 묶음 × 왕복, 브로커가 죽으면 묶음 × 상한이었다.
 * 실제 Kafka 로는 {@code UserOutboxRelayKafkaTest}(컨테이너).
 *
 * <p>[JA] アウトボックスのポーラーのバッチ送信 — すべて送ってから一つの期限内でまとめて待つ。以前は 1 件ずつ送って ack を待っていた。
 */
@DisplayName("아웃박스 폴러 — 묶음을 모두 보낸 뒤 함께 기다린다")
class UserOutboxBatchRelayTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final UserCreatedProducer created = mock(UserCreatedProducer.class);
    private final UserLifecycleProducer lifecycle = mock(UserLifecycleProducer.class);

    private UserOutboxScheduler scheduler(long timeoutMs) {
        UserOutboxScheduler s = new UserOutboxScheduler(repository, created, lifecycle, new ObjectMapper(), OutboxTraceContext.NOOP);
        s.setPublishTimeoutMs(timeoutMs);
        return s;
    }

    private static List<OutboxEvent> updates(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> new OutboxEvent(String.valueOf(100 + i), "User", UserLifecycleProducer.UPDATED, "{\"userId\":" + (100 + i) + "}"))
                .toList();
    }

    private static String status(OutboxEvent e) {
        return e.getOutboxStatus().name();
    }

    @Test
    @DisplayName("🔴 묶음을 다 보낸 뒤에 기다린다 — ack 는 마지막 건을 보낸 뒤에야 오는데도 모두 COMPLETED")
    void sendsWholeBatchBeforeWaitingForAcks() {
        int n = 20;
        List<OutboxEvent> batch = updates(n);
        when(repository.claimPending(any(Integer.class))).thenReturn(batch);
        // 브로커 흉내: 묶음의 마지막 건이 보내질 때 비로소 모든 ack 가 온다 — 한 건씩 기다리면 첫 건에서 기한을 넘긴다
        List<CompletableFuture<Object>> acks = new ArrayList<>();
        doAnswer(inv -> {
            CompletableFuture<Object> f = new CompletableFuture<>();
            acks.add(f);
            if (acks.size() == n) {
                acks.forEach(a -> a.complete(null));
            }
            return f;
        }).when(lifecycle).send(any(), any(), any());

        scheduler(2_000).processOutboxMessage();

        assertThat(batch).extracting(UserOutboxBatchRelayTest::status).containsOnly("COMPLETED");
    }

    @Test
    @DisplayName("🔴 브로커가 ack 를 주지 않아도 트랜잭션은 기한 한 번 — 묶음 × 기한이 아니다(30건 × 300ms = 9초 → 1초 안)")
    void brokerSilenceCostsOneDeadlineNotOnePerRow() {
        List<OutboxEvent> batch = updates(30);
        when(repository.claimPending(any(Integer.class))).thenReturn(batch);
        doAnswer(inv -> new CompletableFuture<>()).when(lifecycle).send(any(), any(), any());

        long t0 = System.nanoTime();
        scheduler(300).processOutboxMessage();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(ms).as("기한 300ms 한 번 + 여유").isLessThan(1_500);
        assertThat(batch).as("ack 가 없으면 FAILED — 예전과 같다").extracting(UserOutboxBatchRelayTest::status).containsOnly("FAILED");
    }

    @Test
    @DisplayName("🔴 같은 사용자의 이벤트는 순서대로 — 가입(user-created)의 ack 뒤에야 같은 사용자의 변경을 보낸다(프로듀서가 다르다)")
    void sameAggregateWaitsForItsPreviousEvent() {
        OutboxEvent signup = new OutboxEvent("42", "User", UserCreatedProducer.TOPIC, "{\"userId\":42,\"username\":\"u42\"}");
        OutboxEvent other = new OutboxEvent("43", "User", UserLifecycleProducer.UPDATED, "{\"userId\":43}");
        OutboxEvent update = new OutboxEvent("42", "User", UserLifecycleProducer.UPDATED, "{\"userId\":42}");
        when(repository.claimPending(any(Integer.class))).thenReturn(List.of(signup, other, update));
        CompletableFuture<Object> signupAck = new CompletableFuture<>();
        doReturn(signupAck).when(created).send(any());
        AtomicBoolean signupAckedBeforeUpdate = new AtomicBoolean();
        AtomicBoolean otherSentBeforeSignupAck = new AtomicBoolean();
        doAnswer(inv -> {
            if ("42".equals(inv.getArgument(1))) {
                signupAckedBeforeUpdate.set(signupAck.isDone());
            } else {
                // 가입의 ack 는 다른 사용자를 보낸 뒤 200ms 에 온다 — 같은 사용자의 변경은 그것을 기다려야 한다
                otherSentBeforeSignupAck.set(!signupAck.isDone());
                CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> signupAck.complete(null));
            }
            return CompletableFuture.completedFuture(null);
        }).when(lifecycle).send(any(), any(), any());

        scheduler(2_000).processOutboxMessage();

        assertThat(signupAckedBeforeUpdate).as("같은 사용자 — 앞 이벤트의 ack 를 기다렸다").isTrue();
        assertThat(otherSentBeforeSignupAck).as("다른 사용자는 기다리지 않는다").isTrue();
        assertThat(List.of(signup, other, update)).extracting(UserOutboxBatchRelayTest::status).containsOnly("COMPLETED");
    }

    @Test
    @DisplayName("🔴 보내기가 바로 실패하면(브로커에 닿지 못함) 그 행만 FAILED — 나머지는 보내지 않고 STARTED 로 다음 주기에")
    void unreachableBrokerStopsTheBatchAndLeavesTheRestStarted() {
        List<OutboxEvent> batch = updates(5);
        when(repository.claimPending(any(Integer.class))).thenReturn(batch);
        doAnswer(inv -> {
            throw new KafkaException("Topic user-updated not present in metadata after 60000 ms.");
        }).when(lifecycle).send(any(), any(), any());

        scheduler(2_000).processOutboxMessage();

        verify(lifecycle, times(1)).send(any(), any(), any());
        assertThat(status(batch.get(0))).isEqualTo("FAILED");
        assertThat(batch.subList(1, 5)).extracting(UserOutboxBatchRelayTest::status).containsOnly("STARTED");
    }

    @Test
    @DisplayName("보낼 수 없는 행(모르는 종류 · 깨진 페이로드)은 그 행만 FAILED — 브로커 탓이 아니라 묶음을 멈추지 않는다")
    void unsendableRowDoesNotStopTheBatch() {
        OutboxEvent unknown = new OutboxEvent("1", "User", "user-renamed", "{}");
        OutboxEvent broken = new OutboxEvent("2", "User", UserCreatedProducer.TOPIC, "not json");
        OutboxEvent fine = new OutboxEvent("3", "User", UserLifecycleProducer.DELETED, "{\"userId\":3}");
        when(repository.claimPending(any(Integer.class))).thenReturn(List.of(unknown, broken, fine));
        doReturn(CompletableFuture.completedFuture(null)).when(lifecycle).send(any(), any(), any());

        scheduler(2_000).processOutboxMessage();

        assertThat(status(unknown)).isEqualTo("FAILED");
        assertThat(status(broken)).isEqualTo("FAILED");
        assertThat(status(fine)).isEqualTo("COMPLETED");
        verify(created, never()).send(any());
        verify(lifecycle).send(eq(UserLifecycleProducer.DELETED), eq("3"), any());
    }
}
