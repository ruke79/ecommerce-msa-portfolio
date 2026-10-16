package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;

import com.project.chat_service.mapper.MessageMapper;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.repository.jpa.ChatRoomJpaRepository;
import com.project.chat_service.repository.jpa.ParticipantChatRoomRepository;
import com.project.chat_service.repository.mongo.ChatMessageRepository;
import com.project.chat_service.repository.redis.ChatRoomRepository;
import com.project.common.domain.exception.DomainException;

/**
 * 방 이름은 1 ~ 20자(화면과 같다) — 넘거나 비면 400.
 *
 * <p>🐛 예전: 이름을 보지 않았다 — 빈 이름의 방이 생기고, 255자를 넘으면 DB 오류가 500 으로 나갔다.
 *
 * <p>[JA] ルーム名は 1 〜 20 字。以前は名前を見ておらず、空の名前のルームができ、長すぎると 500 だった。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatService.createChatRoom — 방 이름")
class ChatServiceRoomNameTest {

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageProducer messageProducer;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock ChatRoomJpaRepository chatRoomJpaRepository;
    @Mock ChatMemberService chatMemberService;
    @Mock ParticipantChatRoomRepository participantChatRoomRepository;
    @Mock SequenceGeneratorService sequenceGenerator;
    @Mock RedissonClient redissonClient;
    @Mock MessageMapper messageMapper;
    @InjectMocks ChatService service;

    @Test
    @DisplayName("🔴 20자를 넘거나 빈 이름은 DomainException(400) — 잠금도 잡지 않는다")
    void rejectsBadNames() {
        for (String name : new String[] { "가".repeat(ChatService.ROOM_NAME_MAX_LENGTH + 1), "   ", null }) {
            assertThatThrownBy(() -> service.createChatRoom(name, 7L)).as(String.valueOf(name))
                    .isInstanceOf(DomainException.class);
        }
        verify(redissonClient, never()).getLock(anyString());
    }
}
