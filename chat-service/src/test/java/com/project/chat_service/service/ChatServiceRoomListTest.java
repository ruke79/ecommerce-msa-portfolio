package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.model.ChatRoom;
import com.project.chat_service.repository.jpa.ChatRoomJpaRepository;
import com.project.chat_service.repository.jpa.ParticipantChatRoomRepository;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.repository.redis.ChatRoomRepository;

/**
 * 채팅방 목록의 출처는 <b>DB</b> 다.
 *
 * <p>🐛 예전에는 Redis 해시에서 읽었다. 방은 DB 에도 저장되는데, Redis 가 비워지면(재시작·
 * 다른 인스턴스) DB 에 멀쩡히 있는 방이 목록에서 사라졌다. 진실은 한 곳 — 트랜잭션이 있는
 * DB — 이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatService.fildAllRooms — 목록은 DB 에서")
class ChatServiceRoomListTest {

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

    @InjectMocks ChatService service;

    /** 🔴 Redis 가 비어 있어도 DB 의 방은 보인다. */
    @Test
    @DisplayName("🔴 Redis 가 비어 있어도 DB 의 방이 목록에 나온다")
    void listComesFromTheDatabase() {
        when(chatRoomJpaRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of(ChatRoom.create("test1"), ChatRoom.create("test2")));

        List<ChatRoom> rooms = service.fildAllRooms();

        assertThat(rooms).extracting(ChatRoom::getRoomName).containsExactly("test1", "test2");
        org.mockito.Mockito.verifyNoInteractions(chatRoomRepository);   // Redis 에는 방이 없다(사본을 없앴다)
    }
}
