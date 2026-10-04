package com.project.chat_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.common.outbox.OutboxTraceContext;

/**
 * 채팅 회원 복제본이 원본의 프로필 변경·탈퇴를 따라온다 (docs/48 §6-⑤).
 *
 * <p>user-created 만 받던 동안 복제본은 가입 순간에서 멈춰 있었다 — 바꾼 닉네임이 채팅에 안 보였고, 탈퇴한 사람의
 * 이메일이 여기에만 남았다.
 */
@DisplayName("채팅 회원 복제본 — user-updated·user-deleted")
class UserLifecycleConsumerTest {

    private static final long USER_ID = 1234567890123456789L;

    private ChatMemberRepository repository;
    private org.springframework.data.mongodb.core.MongoTemplate mongo;
    private UserLifecycleConsumer consumer;
    private ChatMember member;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMemberRepository.class);
        mongo = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        when(mongo.updateMulti(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),
                org.mockito.ArgumentMatchers.<Class<?>>any()))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));
        consumer = new UserLifecycleConsumer(new UserMessageListener(repository, mongo), new ObjectMapper(),
                new OutboxTraceContext(() -> null, () -> null));
        member = ChatMember.builder().id(USER_ID)
                .nickname("옛이름").imageUrl("old.png").build();
        when(repository.findForUpdate(USER_ID)).thenReturn(Optional.of(member));
    }

    private static String updated(String nickname, String image, long at) {
        return "{\"userId\":" + USER_ID + ",\"nickname\":" + (nickname == null ? "null" : "\"" + nickname + "\"")
                + ",\"image\":" + (image == null ? "null" : "\"" + image + "\"")
                + ",\"updatedAt\":" + at + ",\"eventId\":\"user-updated:" + USER_ID + ":" + at + "\"}";
    }

    @Test
    @DisplayName("프로필 변경이 복제본에 반영된다")
    void updateIsApplied() {
        consumer.updated(List.of(updated("새이름", "new.png", 1000)), Map.of());

        assertThat(member.getNickname()).isEqualTo("새이름");
        assertThat(member.getImageUrl()).isEqualTo("new.png");
    }

    @Test
    @DisplayName("🔴 늦게 도착한 옛 변경은 새 값을 덮어쓰지 않는다 — 릴레이 재시도로 순서가 뒤집힐 수 있다")
    void staleUpdateIsIgnored() {
        consumer.updated(List.of(updated("최신", "new.png", 2000), updated("옛것", "old2.png", 1000)), Map.of());

        assertThat(member.getNickname()).isEqualTo("최신");
        assertThat(member.getImageUrl()).isEqualTo("new.png");
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 결과가 같다")
    void duplicateUpdateIsHarmless() {
        String e = updated("새이름", "new.png", 1000);
        consumer.updated(List.of(e, e), Map.of());

        assertThat(member.getNickname()).isEqualTo("새이름");
    }

    @Test
    @DisplayName("빈 닉네임은 지금 값을 둔다 — 이미지는 지울 수 있다")
    void blankNicknameKeepsCurrent() {
        consumer.updated(List.of(updated(" ", null, 1000)), Map.of());

        assertThat(member.getNickname()).isEqualTo("옛이름");
        assertThat(member.getImageUrl()).isNull();
    }

    @Test
    @DisplayName("🔴 탈퇴하면 이메일·이름·사진이 복제본에서도 지워진다 — 행은 남는다")
    void deleteAnonymizes() {
        consumer.deleted(List.of("{\"userId\":" + USER_ID + ",\"eventId\":\"user-deleted:" + USER_ID + "\"}"), Map.of());

        assertThat(member.getNickname()).isEqualTo(ChatMember.WITHDRAWN_NICKNAME);
        assertThat(member.getImageUrl()).isNull();
        verify(repository, never()).delete(member);
    }

    @Test
    @DisplayName("🔴 탈퇴 뒤에 늦게 온 프로필 변경은 되살리지 못한다")
    void updateAfterDeleteIsIgnored() {
        consumer.deleted(List.of("{\"userId\":" + USER_ID + "}"), Map.of());
        consumer.updated(List.of(updated("되살아남", "new.png", System.currentTimeMillis())), Map.of());

        assertThat(member.getNickname()).isEqualTo(ChatMember.WITHDRAWN_NICKNAME);
        assertThat(member.getImageUrl()).isNull();
    }

    /**
     * 🔒 대화 기록(몽고)에도 흔적이 있다 — 메시지마다 닉네임 사본, 옛 문서(v1)는 발신자 이메일. 회원 행만 지우면 그것이
     * 남는다. 옛 문서가 아직 백필되지 않았으면 발신자가 이메일이다 — 매핑(V5)에서 찾아 함께 지우고, 매핑도 지운다.
     */
    @Test
    @DisplayName("🔴 탈퇴하면 대화 기록의 발신자 표시(닉네임 사본)도 지운다")
    void deleteScrubsMessageHistory() {
        consumer.deleted(List.of("{\"userId\":" + USER_ID + "}"), Map.of());

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> q =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.UpdateDefinition> u =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.UpdateDefinition.class);
        verify(mongo, org.mockito.Mockito.times(3)).updateMulti(q.capture(), u.capture(),
                org.mockito.ArgumentMatchers.eq(com.project.chat_service.model.ChatMessage.class));

        String who = q.getAllValues().get(2).getQueryObject().toJson();
        assertThat(who).contains("senderId").doesNotContain("\"email\"");
        String update = u.getAllValues().get(2).getUpdateObject().toJson();
        assertThat(update).contains(ChatMember.WITHDRAWN_NICKNAME);
        assertThat(u.getAllValues().get(0).getUpdateObject().toJson()).contains(ChatMember.WITHDRAWN_NICKNAME + " entered.");
    }

    @Test
    @DisplayName("복제본이 없으면 건너뛴다 — 실패로 DLT 에 보내지 않는다")
    void missingMemberIsSkipped() {
        when(repository.findForUpdate(anyLong())).thenReturn(Optional.empty());

        consumer.updated(List.of(updated("새이름", "new.png", 1000)), Map.of());
        consumer.deleted(List.of("{\"userId\":1}"), Map.of());
    }

    @Test
    @DisplayName("userId 가 없는 레코드는 그 인덱스로 실패를 보고한다 — 앞의 것은 처리된다")
    void recordWithoutUserIdFailsAtItsIndex() {
        assertThatThrownBy(() -> consumer.updated(
                List.of(updated("새이름", "new.png", 1000), "{\"nickname\":\"x\"}"), Map.of()))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(e -> assertThat(((BatchListenerFailedException) e).getIndex()).isEqualTo(1));

        assertThat(member.getNickname()).isEqualTo("새이름");
    }

    // ── user-created 와의 관계 ──────────────────────────────────────────────

    private void created(String nickname, String image) {
        new UserMessageListener(repository, mongo).userCreated(com.project.common.message.dto.request.UserCreatedRequest
                .builder().userId(USER_ID).nickname(nickname).image(image).build());
    }

    /** 이메일은 복제하지 않는다 — 회원 행에 컬럼 자체가 없다(V5, docs/48 §6-⑥). */
    @Test
    @DisplayName("🔒 새 회원은 id·닉네임·사진만으로 복제된다")
    void createdStoresNoEmail() {
        when(repository.findForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        created("새사람", "me.png");

        org.mockito.ArgumentCaptor<ChatMember> saved = org.mockito.ArgumentCaptor.forClass(ChatMember.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(java.util.Arrays.stream(ChatMember.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("email");
        assertThat(saved.getValue().getNickname()).isEqualTo("새사람");
    }

    @Test
    @DisplayName("🔴 접속 순간 즉석으로 만든 회원은 늦게 온 가입 이벤트가 원본 프로필로 채운다 — 예전에는 충돌을 삼키고 끝났다")
    void createdFillsOnTheFlyMember() {
        created("진짜이름", "me.png");

        assertThat(member.getNickname()).isEqualTo("진짜이름");
        assertThat(member.getImageUrl()).isEqualTo("me.png");
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("가입 이벤트가 프로필 변경보다 늦게 와도 새 값을 덮지 않는다")
    void createdAfterUpdateDoesNotOverwrite() {
        consumer.updated(List.of(updated("바꾼이름", "new.png", 1000)), Map.of());
        created("가입때이름", "first.png");

        assertThat(member.getNickname()).isEqualTo("바꾼이름");
    }

    @Test
    @DisplayName("가입 이벤트가 탈퇴한 회원을 되살리지 않는다")
    void createdAfterDeleteDoesNotRevive() {
        consumer.deleted(List.of("{\"userId\":" + USER_ID + "}"), Map.of());
        created("가입때이름", "first.png");

        assertThat(member.getNickname()).isEqualTo(ChatMember.WITHDRAWN_NICKNAME);
    }
}
