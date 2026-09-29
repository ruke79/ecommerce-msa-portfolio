package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxTraceContext;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.producer.UserLifecycleProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;
import com.project.userservice.model.User;

/**
 * 🐛 복제본이 가입 순간의 값에서 멈춰 있었다 — 프로필 변경·탈퇴를 알린다 (docs/48 §6-⑤).
 *
 * <p>{@code user-created} 만 있던 동안 닉네임·사진을 바꿔도 채팅에는 옛 값이 보였고, 탈퇴로 원본을 익명화해도 chat 의
 * 복제본에는 이메일이 남았다. 두 사건을 가입과 같은 아웃박스로 적고, 같은 릴레이가 보낸다.
 */
@DisplayName("사용자 변경·탈퇴 이벤트 — 아웃박스")
class UserLifecycleOutboxTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserOutboxHelper helper = new UserOutboxHelper(repository, objectMapper, OutboxTraceContext.NOOP, OutboxRelayTrigger.DISABLED);

    private User user() {
        User u = User.builder().email("buyer@example.com").username("buyer").build();
        u.setUserId(42L);
        u.setName("새 이름");
        u.setImage("https://cdn/p.png");
        return u;
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /** 🔒 가입 이벤트에도 이메일을 싣지 않는다 — 소비자(coupon·chat) 누구도 쓰지 않는다(docs/48 §6-⑥). */
    @Test
    @DisplayName("🔒 가입 — user-created 에 이메일을 싣지 않는다")
    void createdCarriesNoEmail() throws Exception {
        helper.recordUserCreated(user());

        var payload = objectMapper.readTree(saved().getPayload());
        assertThat(payload.get("userId").asLong()).isEqualTo(42L);
        assertThat(payload.get("nickname").asText()).isEqualTo("새 이름");
        assertThat(payload.path("email").isNull() || payload.path("email").isMissingNode()).isTrue();
        assertThat(saved().getPayload()).doesNotContain("buyer@example.com");
    }

    @Test
    @DisplayName("🔴 프로필 변경 — user-updated 에 닉네임·이미지를 싣고, 이메일은 싣지 않는다")
    void updatedCarriesDisplayFieldsOnly() throws Exception {
        helper.recordUserUpdated(user());

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo(UserLifecycleProducer.UPDATED);
        assertThat(event.getAggregateId()).isEqualTo("42");
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("userId").asLong()).isEqualTo(42L);
        assertThat(payload.get("nickname").asText()).isEqualTo("새 이름");
        assertThat(payload.get("image").asText()).isEqualTo("https://cdn/p.png");
        assertThat(payload.has("updatedAt")).isTrue();
        assertThat(event.getPayload()).as("데이터 최소화 — 받는 쪽이 쓰지 않는 이메일").doesNotContain("buyer@example.com");
    }

    @Test
    @DisplayName("🔴 탈퇴 — user-deleted 는 id 뿐이다")
    void deletedCarriesOnlyTheId() throws Exception {
        helper.recordUserDeleted(42L);

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo(UserLifecycleProducer.DELETED);
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("userId").asLong()).isEqualTo(42L);
        assertThat(payload.get("eventId").asText()).isEqualTo("user-deleted:42");
    }

    /** 가입 이벤트와 같은 이유 — REQUIRED 면 트랜잭션 없이 불렸을 때 새 트랜잭션을 열어 dual-write 가 된다. */
    @Test
    @DisplayName("두 기록 모두 MANDATORY — 원본 변경과 같은 트랜잭션에서만 적힌다")
    void recordsAreMandatory() throws Exception {
        for (String name : List.of("recordUserUpdated", "recordUserDeleted")) {
            var method = java.util.Arrays.stream(UserOutboxHelper.class.getMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(Transactional.class).propagation()).as(name).isEqualTo(Propagation.MANDATORY);
        }
    }

    @Test
    @DisplayName("🔴 릴레이 — 변경·탈퇴 행은 그 토픽으로 페이로드를 그대로 보내고 완료로 찍는다")
    void relayDispatchesLifecycleEvents() {
        UserCreatedProducer created = mock(UserCreatedProducer.class);
        UserLifecycleProducer lifecycle = mock(UserLifecycleProducer.class);
        UserOutboxScheduler scheduler = new UserOutboxScheduler(repository, created, lifecycle, objectMapper,
                OutboxTraceContext.NOOP);
        OutboxEvent updated = new OutboxEvent("42", "User", UserLifecycleProducer.UPDATED, "{\"userId\":42}");
        OutboxEvent deleted = new OutboxEvent("42", "User", UserLifecycleProducer.DELETED, "{\"userId\":42}");
        when(repository.claimPending(any(Integer.class))).thenReturn(List.of(updated, deleted));

        scheduler.processOutboxMessage();

        verify(lifecycle).publish(UserLifecycleProducer.UPDATED, "42", "{\"userId\":42}");
        verify(lifecycle).publish(UserLifecycleProducer.DELETED, "42", "{\"userId\":42}");
        verify(created, never()).publish(any());
        assertThat(updated.getOutboxStatus().name()).isEqualTo("COMPLETED");
        assertThat(deleted.getOutboxStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("보내다 실패하면 FAILED 로 남긴다 — 무엇이 안 나갔는지 셀 수 있다")
    void relayMarksFailure() {
        UserLifecycleProducer lifecycle = mock(UserLifecycleProducer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(lifecycle).publish(eq(UserLifecycleProducer.UPDATED), any(), any());
        UserOutboxScheduler scheduler = new UserOutboxScheduler(repository, mock(UserCreatedProducer.class), lifecycle,
                objectMapper, OutboxTraceContext.NOOP);
        OutboxEvent updated = new OutboxEvent("42", "User", UserLifecycleProducer.UPDATED, "{}");
        when(repository.claimPending(any(Integer.class))).thenReturn(List.of(updated));

        scheduler.processOutboxMessage();

        assertThat(updated.getOutboxStatus().name()).isEqualTo("FAILED");
    }
}
