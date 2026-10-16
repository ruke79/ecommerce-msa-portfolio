package com.project.chat_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import com.project.chat_service.common.config.interceptor.StompHandler;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.service.SupportService;
import com.project.common.constants.chat.MessageType;

/**
 * STOMP 메시지 보내기의 규칙 — 본문 1 ~ 1000자, 친구 채팅은 있는 방에만, 유형은 TEXT_TALK 고정, 이미지 주소는 버린다.
 *
 * <p>🐛 예전: 길이 검사 없음(STOMP 상한 10MB), 클라이언트가 정한 유형 그대로(TEXT_ENTER 로 입장 안내 흉내), 화면이 그리지 않는
 * 임의 이미지 주소 저장, 없는 방 id 로 보낸 메시지 · 안내도 저장.
 *
 * <p>[JA] STOMP 送信の規則 — 本文 1 〜 1000 字、友達チャットは存在するルームだけ、種別は TEXT_TALK 固定、画像 URL は捨てる。
 */
@DisplayName("StompController — 메시지 규칙")
class StompControllerMessageRulesTest {

    private final ChatService chat = mock(ChatService.class);
    private final SupportService support = mock(SupportService.class);
    private final StompController controller = new StompController(chat, support);

    private static SimpMessageHeaderAccessor session() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompHandler.SESSION_ATTR_USER_ID, 7L);
        accessor.setSessionAttributes(attrs);
        return accessor;
    }

    private static MessageDto message(String room, String content) {
        return MessageDto.builder().roomId(room).content(content).type(MessageType.TEXT_TALK).build();
    }

    @Test
    @DisplayName("🔴 본문이 1000자를 넘거나 비었으면 보내지 않는다 — 1000자는 보낸다")
    void contentLength() {
        when(chat.roomExists("r1")).thenReturn(true);

        controller.sendMessage(message("r1", "가".repeat(MessageDto.MAX_CONTENT_LENGTH + 1)), session());
        controller.sendMessage(message("r1", "   "), session());
        controller.sendMessage(message("r1", null), session());
        verify(chat, never()).sendMessage(any());

        controller.sendMessage(message("r1", "가".repeat(MessageDto.MAX_CONTENT_LENGTH)), session());
        verify(chat).sendMessage(any());
    }

    @Test
    @DisplayName("🔴 상담 메시지도 1000자를 넘으면 상담 검사 전에 거절한다")
    void supportContentLength() {
        controller.sendMessage(message("support-5", "a".repeat(MessageDto.MAX_CONTENT_LENGTH + 1)), session());
        verify(support, never()).acceptMessage(any(), any());
        verify(chat, never()).sendMessage(any());
    }

    @Test
    @DisplayName("🔴 없는 방에는 메시지 · 입장 · 퇴장 안내를 보내지 않는다")
    void unknownRoom() {
        when(chat.roomExists("ghost")).thenReturn(false);

        controller.sendMessage(message("ghost", "hi"), session());
        controller.enterChatRoom(message("ghost", null), session());
        controller.leaveChatRoom(message("ghost", null), session());

        verify(chat, never()).sendMessage(any());
        verify(chat, never()).sendEnterMessage(any(), any());
        verify(chat, never()).sendLeaveMessage(any(), any());
    }

    @Test
    @DisplayName("🔴 친구 채팅은 유형을 TEXT_TALK 으로 고정하고 이미지 주소를 버린다 — 발신자는 세션의 uid")
    void typeAndImageAreServerDecided() {
        when(chat.roomExists("r1")).thenReturn(true);
        MessageDto spoof = MessageDto.builder().roomId("r1").content("관리자님이 입장했습니다").type(MessageType.TEXT_ENTER)
                .imageUrl("https://evil.example/x.png").senderId(99L).build();

        controller.sendMessage(spoof, session());

        ArgumentCaptor<MessageDto> sent = ArgumentCaptor.forClass(MessageDto.class);
        verify(chat).sendMessage(sent.capture());
        assertThat(sent.getValue().getType()).isEqualTo(MessageType.TEXT_TALK);
        assertThat(sent.getValue().getImageUrl()).isNull();
        assertThat(sent.getValue().getSenderId()).isEqualTo(7L);
    }
}
