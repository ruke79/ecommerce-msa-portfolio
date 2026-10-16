package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatMember;
import com.project.chat_service.model.ChatRoom;
import com.project.chat_service.repository.jpa.ChatRoomJpaRepository;
import com.project.chat_service.repository.jpa.ParticipantChatRoomRepository;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.repository.redis.ChatRoomRepository;

import jakarta.persistence.EntityManager;

/**
 * 구독(입장) 시의 참여자 등록.
 *
 * <h3>🐛 회귀 대상 — "어떤 방에서는 구독이 실패한다"</h3>
 *
 * <p>참여자 행을 만들 때 방 객체를 <b>Redis 에서</b> 읽어 연관으로 걸었다. 그것은
 * 역직렬화된 분리 객체라 JPA 가 관리하지 않고, 저장하면 flush 단계에서 터진다. 그 예외가
 * SUBSCRIBE 프레임을 죽여 클라이언트에는 {@code Failed to send message to
 * ExecutorSubscribableChannel[clientInboundChannel]} 만 보였다.
 *
 * <p><b>자기가 만든 방은 멀쩡했다</b> — 이미 참여자라 저장 자체를 건너뛰기 때문이다. 그래서
 * "남이 만든 방(또는 예전에 만든 방)에서만 실패" 로 나타났다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatService.connect — 구독 시 참여자 등록")
class ChatServiceConnectTest {

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageProducer messageProducer;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatRoomJpaRepository chatRoomJpaRepository;
    @Mock ChatMemberService chatMemberService;
    @Mock ParticipantChatRoomRepository participantChatRoomRepository;
    @Mock SequenceGeneratorService sequenceGenerator;
    @Mock RedissonClient redissonClient;
    @Mock MongoTemplate mongoTemplate;
    @Mock MessageMapper messageMapper;
    @Mock EntityManager em;

    ChatService service;

    private static final String ROOM = "room-uid-1";
    private static final String SESSION = "sess-1";

    @BeforeEach
    void setUp() {
        service = new ChatService(chatMessageRepository, messageProducer, chatRoomRepository,
                chatRoomJpaRepository, chatMemberService, participantChatRoomRepository,
                sequenceGenerator, redissonClient, messageMapper);
        ReflectionTestUtils.setField(service, "em", em);
    }

    private ChatRoom dbRoom() {
        ChatRoom room = ChatRoom.create("test1");
        when(chatRoomJpaRepository.findByRoomUid(ROOM)).thenReturn(Optional.of(room));
        return room;
    }

    private ChatMember member() {
        ChatMember member = ChatMember.builder().id(7L).nickname("someone").build();
        when(chatMemberService.findById(7L)).thenReturn(member);
        return member;
    }

    /**
     * 🔴 <b>이 파일의 핵심.</b> 참여자 행의 방은 <b>DB 에서 읽은 것</b>이어야 한다.
     * Redis 의 방 객체는 쳐다보지도 않는다.
     */
    @Test
    @DisplayName("🔴 참여자 행은 DB 의 방으로 만든다 — Redis 의 방 객체를 쓰지 않는다")
    void participantRowUsesTheManagedRoom() {
        ChatRoom room = dbRoom();
        ChatMember member = member();
        when(participantChatRoomRepository.existsByRoom_RoomUidAndMember_Id(ROOM, 7L))
                .thenReturn(false);

        service.connect(ROOM, SESSION, 7L);

        verify(chatRoomJpaRepository).findByRoomUid(ROOM);
        // DB 의 방(그 id)으로, 없을 때만 넣는다(V9 유니크 — 동시 구독이 참여를 두 번 남기지 않게).
        verify(participantChatRoomRepository).insertIfAbsent(room.getId(), member.getId());
    }

    /** 방을 만든 사람은 이미 참여자다 — 다시 저장하지 않는다(그래서 예전 버그가 안 보였다). */
    @Test
    @DisplayName("이미 참여자면 다시 저장하지 않는다")
    void existingParticipantIsNotSavedAgain() {
        dbRoom();
        member();
        when(participantChatRoomRepository.existsByRoom_RoomUidAndMember_Id(ROOM, 7L))
                .thenReturn(true);

        service.connect(ROOM, SESSION, 7L);

        verify(participantChatRoomRepository, never()).insertIfAbsent(any(), any());
    }

    /** 세션↔방 매핑이 먼저다 — 참여자 등록이 실패해도 DISCONNECT 가 정리할 것은 남는다. */
    @Test
    @DisplayName("참여자 등록이 실패해도 세션↔방 매핑은 남는다")
    void enterInfoIsRecordedFirst() {
        when(chatRoomJpaRepository.findByRoomUid(ROOM)).thenReturn(Optional.empty());
        member();

        assertThatThrownBy(() -> service.connect(ROOM, SESSION, 7L))
                .isInstanceOf(com.project.common.domain.exception.DomainException.class)
                .hasMessageContaining("채팅방을 찾을 수 없습니다");

        verify(chatRoomRepository).setUserEnterInfo(SESSION, ROOM);
    }

    /**
     * 회원 복제 행이 없는 사용자도 구독할 수 있어야 한다. 예전에는 이 경로가 userId 를
     * 항상 null 로 넘겨, 그런 사용자는 <b>어느 방에도</b> 들어가지 못했다.
     */
    @Test
    @DisplayName("회원 행이 없으면 토큰의 userId 로 즉석에서 만든다")
    void missingMemberRowIsCreatedFromTokenUserId() {
        dbRoom();
        when(chatMemberService.findById(7L)).thenReturn(null);
        when(participantChatRoomRepository.existsByRoom_RoomUidAndMember_Id(ROOM, 7L))
                .thenReturn(false);

        service.connect(ROOM, SESSION, 7L);

        verify(em).flush();
        // 즉석 생성 행은 id 하나로 만든다 — 신원은 id 다(docs/48 §6-⑥).
        org.mockito.ArgumentCaptor<Object> persisted = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(em, org.mockito.Mockito.atLeastOnce()).persist(persisted.capture());
        ChatMember created = (ChatMember) persisted.getAllValues().stream()
                .filter(ChatMember.class::isInstance).findFirst().orElseThrow();
        assertThat(created.getId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("회원 행도 없고 userId 도 모르면 무엇이 없는지 말한다")
    void missingMemberWithoutUserIdIsExplained() {
        dbRoom();
        when(chatMemberService.findById(7L)).thenReturn(null);

        assertThatThrownBy(() -> service.connect(ROOM, SESSION, null))
                .isInstanceOf(com.project.common.domain.exception.DomainException.class)
                .hasMessageContaining("채팅 회원 정보가 없습니다");
    }
}
