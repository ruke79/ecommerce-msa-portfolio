package com.project.chat_service.message.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.service.SupportDeskNotifier;
import com.project.common.constants.chat.MessageType;

/**
 * 상담 메시지가 저장되면 데스크에 알린다 (docs/58 §9).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageConsumer — 상담 데스크 알림")
class MessageConsumerDeskTest {

    @Mock SimpMessageSendingOperations template;
    @Mock ChatService chatService;
    @Mock SupportDeskNotifier deskNotifier;
    @Mock com.project.chat_service.service.SupportReplyNotifier replyNotifier;
    @InjectMocks MessageConsumer consumer;

    private static MessageDto message(String roomId, MessageType type) {
        MessageDto m = MessageDto.builder().id("10").roomId(roomId).type(type).content("hi").build();
        m.setSenderId(1001L);
        return m;
    }

    @Test
    @DisplayName("상담 방의 대화는 저장·브로드캐스트 뒤 데스크에 MESSAGE")
    void supportTalkNotifies() {
        consumer.receiveMessage(message("support-5", MessageType.TEXT_TALK));

        verify(chatService).saveMessage(any());
        verify(deskNotifier).notify(5L, SupportDeskNotifier.Kind.MESSAGE, 1001L);
        verify(replyNotifier).onMessage(any());
    }

    @Test
    @DisplayName("친구 채팅방·안내 메시지는 데스크에 알리지 않는다")
    void othersDoNot() {
        consumer.receiveMessage(message("4f1c2e3a-uuid", MessageType.TEXT_TALK));
        consumer.receiveMessage(message("support-5", MessageType.TEXT_ENTER));
        consumer.receiveMessage(message("support-abc", MessageType.TEXT_TALK));

        verify(deskNotifier, never()).notify(any(), any(), any());
        verify(replyNotifier, never()).onMessage(any());
    }
}
