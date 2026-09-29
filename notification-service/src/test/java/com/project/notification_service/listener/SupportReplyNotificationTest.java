package com.project.notification_service.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.service.NotificationService;

/**
 * 고객센터 상담원 답변 알림 (docs/58 §10).
 */
@DisplayName("notification-service — 상담원 답변 알림")
class SupportReplyNotificationTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener =
            new NotificationEventListener(notificationService, new ObjectMapper(), com.project.common.outbox.OutboxTraceContext.NOOP);

    @Test
    @DisplayName("고객에게 '답변이 왔다' 알림 — 상담 화면으로 가는 링크, 메시지 단위로 한 번")
    void notifiesCustomer() {
        listener.onSupportReply(List.of(
                "{\"receiverId\": 1001, \"threadId\": 5, \"messageId\": \"42\", \"senderName\": \"민지\"}"), null);

        verify(notificationService).create(eq(1001L), eq(NotificationType.CHAT_MESSAGE), eq("고객지원팀 답변"),
                anyString(), eq("support"), eq("support-reply:42"), any());
    }

    @Test
    @DisplayName("🔒 알림에 상담 내용이 들어가지 않는다 — 이벤트에 본문이 있어도 쓰지 않는다")
    void noContentInNotification() {
        listener.onSupportReply(List.of(
                "{\"receiverId\": 1001, \"threadId\": 5, \"messageId\": \"42\", \"content\": \"환불 계좌는 110-...\"}"), null);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(anyLong(), any(), title.capture(), body.capture(), anyString(), anyString(), any());
        org.assertj.core.api.Assertions.assertThat(title.getValue() + body.getValue()).doesNotContain("110");
    }

    @Test
    @DisplayName("받는 사람이 없으면 버린다 — DLT 로 보내지 않는다")
    void skipsIncomplete() {
        listener.onSupportReply(List.of("{\"threadId\": 5, \"messageId\": \"42\"}"), null);

        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
