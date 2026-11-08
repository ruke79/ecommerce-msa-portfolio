package com.project.chat_service.message.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.message.fanout.StompFanout;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.service.SupportDeskNotifier;
import com.project.chat_service.service.SupportReplyNotifier;
import com.project.common.constants.chat.MessageType;

/**
 * 다시 읽은 옛 채팅 레코드 — 저장만 하고 뿌리지 · 알리지 않는다.
 *
 * <p>그룹(chat)의 오프셋 기본값이 earliest 가 됐다(B-110, docs/89 §2-1): 그룹이 처음 뜨거나 오프셋이 만료되면 토픽 보존 안의 옛 메시지를
 * 다시 읽는다. 저장은 같은 _id 라 멱등이지만, 며칠 전 메시지를 지금 방에 다시 뿌리거나 상담 데스크 · 고객에게 다시 알리면 안 된다.
 *
 * <p>[JA] 読み直した古いチャットレコード — 保存だけで配信 · 通知しない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageConsumer — 다시 읽은 옛 레코드")
class MessageConsumerReplayTest {

    @Mock StompFanout fanout;
    @Mock ChatService chatService;
    @Mock SupportDeskNotifier deskNotifier;
    @Mock SupportReplyNotifier replyNotifier;
    @InjectMocks MessageConsumer consumer;

    private static MessageDto supportTalk() {
        MessageDto m = MessageDto.builder().id("10").roomId("support-5").type(MessageType.TEXT_TALK).content("hi").build();
        m.setSenderId(1001L);
        return m;
    }

    @Test
    @DisplayName("🔴 10분보다 오래된 레코드는 저장만 — 뿌리기 · 데스크 · 답변 알림 없음")
    void oldRecordIsOnlyStored() {
        consumer.receiveMessage(supportTalk(), System.currentTimeMillis() - java.time.Duration.ofHours(3).toMillis());

        verify(chatService).saveMessage(any());
        verify(fanout, never()).send(any(), any());
        verify(deskNotifier, never()).notify(any(), any(), any());
        verify(replyNotifier, never()).onMessage(any());
    }

    @Test
    @DisplayName("지금 레코드는 저장 · 뿌리기 · 상담 알림")
    void liveRecordIsDelivered() {
        consumer.receiveMessage(supportTalk(), System.currentTimeMillis() - 2_000);

        verify(chatService).saveMessage(any());
        verify(fanout).send(eq("/sub/chat/room/support-5"), any());
        verify(deskNotifier).notify(5L, SupportDeskNotifier.Kind.MESSAGE, 1001L);
    }
}
