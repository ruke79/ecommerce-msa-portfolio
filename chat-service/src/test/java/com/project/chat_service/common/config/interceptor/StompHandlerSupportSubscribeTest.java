package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.service.SupportService;

/**
 * 🔒 상담 구독 권한 (docs/58).
 *
 * <p>친구 채팅방은 공개라 구독에 권한 검사가 없었다. 상담은 고객 본인과 상담원만 들어야 한다. 그리고 권한은
 * <b>실제 구독 주소</b>로 판정한다 — roomId 헤더는 클라이언트가 정하는 값이고, 기본 브로커는 구독 주소를
 * <b>패턴</b>으로 맞춘다({@code /sub/**} 를 구독하면 모든 방이 온다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StompHandler — 상담 구독 권한")
class StompHandlerSupportSubscribeTest {

    @Mock TokenHandler tokenHandler;
    @Mock ChatService chatService;
    @Mock SupportService supportService;

    StompHandler handler;
    final com.project.chat_service.service.SupportPresence presence = new com.project.chat_service.service.SupportPresence();

    @BeforeEach
    void setUp() {
        handler = new StompHandler();
        ReflectionTestUtils.setField(handler, "tokenHandler", tokenHandler);
        ReflectionTestUtils.setField(handler, "chatService", chatService);
        LiveSessionRegistry live = org.mockito.Mockito.mock(LiveSessionRegistry.class);
        when(live.allowed(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(handler, "liveSessions", live);
        ReflectionTestUtils.setField(handler, "supportService", supportService);
        ReflectionTestUtils.setField(handler, "supportPresence", presence);
    }

    private static Message<byte[]> subscribe(String destination, String roomHeader, Long userId, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("sess-1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() + 600_000L);
        if (role != null) {
            attrs.put("userRole", role);
        }
        accessor.setSessionAttributes(attrs);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        accessor.setNativeHeader("roomId", roomHeader);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("🔴 와일드카드 구독(/sub/**, /sub/chat/room/*)은 거절한다 — 모든 방이 새지 않게")
    void wildcardRejected() {
        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/**", "room-1", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/chat/room/*", "room-1", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/chat/room/support-*", "support-1", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("🔴 남의 상담은 구독할 수 없다")
    void othersThreadRejected() {
        when(supportService.canSubscribe("support-5", 7L, null)).thenReturn(false);

        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("🔴 헤더에 공개 방을 적고 주소로 남의 상담을 구독해도 거절한다 — 판정은 주소로")
    void headerSpoofRejected() {
        when(supportService.canSubscribe(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/chat/room/support-5", "room-1", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
        verify(chatService, never()).connect(any(), any(), any());
    }

    @Test
    @DisplayName("자기 상담은 구독한다 — 친구 채팅방 참여자 표에는 적지 않는다")
    void ownThreadAllowed() {
        when(supportService.canSubscribe("support-5", 7L, null)).thenReturn(true);

        Message<?> result = handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 7L, null), null);

        assertThat(result).isNotNull();
        verify(chatService, never()).connect(any(), any(), any());
    }

    @Test
    @DisplayName("상담원 역할은 세션의 역할로 넘긴다 — CONNECT 때 토큰에서 읽은 값")
    void agentRoleFromSession() {
        when(supportService.canSubscribe("support-5", 9L, "ROLE_SUPPORT")).thenReturn(true);

        handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 9L, "ROLE_SUPPORT"), null);

        verify(supportService).canSubscribe(eq("support-5"), eq(9L), eq("ROLE_SUPPORT"));
    }

    @Test
    @DisplayName("공개 방 구독은 예전 그대로 — 권한 검사 없이 참여자로 적는다")
    void publicRoomUnchanged() {
        handler.preSend(subscribe("/sub/chat/room/4f1c2e3a-uuid", "4f1c2e3a-uuid", 7L, null), null);

        verify(chatService).connect(eq("4f1c2e3a-uuid"), eq("sess-1"), eq(7L));
        verify(supportService, never()).canSubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("CONNECT 는 토큰의 역할을 세션에 담는다")
    void connectStoresRole() {
        when(tokenHandler.verifyToken("tok")).thenReturn(true);
        when(tokenHandler.getUserId("tok")).thenReturn(9L);
        when(tokenHandler.getRole("tok")).thenReturn("ROLE_SUPPORT");
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", "tok");
        accessor.setLeaveMutable(true);
        Message<byte[]> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        handler.preSend(connect, null);

        assertThat(StompHeaderAccessor.wrap(connect).getSessionAttributes()).containsEntry("userRole", "ROLE_SUPPORT");
    }

    // ── 데스크 알림 구독(§9) ────────────────────────────────────────────

    private static Message<byte[]> subscribeDesk(Long userId, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("sess-1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() + 600_000L);
        if (role != null) {
            attrs.put("userRole", role);
        }
        accessor.setSessionAttributes(attrs);
        accessor.setDestination("/sub/support/desk");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("🔒 데스크 알림은 상담원·관리자만 구독한다")
    void deskAgentsOnly() {
        assertThat(handler.preSend(subscribeDesk(9L, "ROLE_SUPPORT"), null)).isNotNull();
        assertThat(handler.preSend(subscribeDesk(9L, "ROLE_ADMIN"), null)).isNotNull();
        assertThatThrownBy(() -> handler.preSend(subscribeDesk(7L, "ROLE_USER"), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> handler.preSend(subscribeDesk(7L, null), null)).isInstanceOf(RuntimeException.class);
        verify(chatService, never()).connect(any(), any(), any());
    }

    // ── SEND 주소(§9) ──────────────────────────────────────────────────

    private static Message<byte[]> send(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("sess-1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 7L);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() + 600_000L);
        accessor.setSessionAttributes(attrs);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage("{\"content\":\"가짜\"}".getBytes(), accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("🔴 브로커 주소(/sub/**)로 직접 SEND 하면 거절 — 컨트롤러의 검사를 건너뛰고 남의 방에 끼워 넣는 길")
    void sendToBrokerRejected() {
        assertThatThrownBy(() -> handler.preSend(send("/sub/chat/room/support-5"), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> handler.preSend(send("/sub/support/desk"), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> handler.preSend(send("/sub/chat/room/4f1c2e3a-uuid"), null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("애플리케이션 주소(/pub/**)로의 SEND 는 그대로 통과한다")
    void sendToAppAllowed() {
        assertThat(handler.preSend(send("/pub/chat/message"), null)).isNotNull();
    }

    // ── 보고 있는가(§10) ───────────────────────────────────────────────

    @Test
    @DisplayName("상담 구독은 '보고 있다' 로 적고, 구독 해지·연결 끊김에 지운다")
    void presenceTracked() {
        when(supportService.canSubscribe("support-5", 7L, null)).thenReturn(true);
        handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 7L, null), null);
        assertThat(presence.watching(7L, "support-5")).isTrue();

        StompHeaderAccessor unsub = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        unsub.setSessionId("sess-1");
        unsub.setSubscriptionId("sub-0");
        unsub.setSessionAttributes(new HashMap<>());
        unsub.setLeaveMutable(true);
        handler.preSend(MessageBuilder.createMessage(new byte[0], unsub.getMessageHeaders()), null);
        assertThat(presence.watching(7L, "support-5")).isFalse();

        handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 7L, null), null);
        StompHeaderAccessor disc = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disc.setSessionId("sess-1");
        disc.setSessionAttributes(new HashMap<>());
        disc.setLeaveMutable(true);
        handler.preSend(MessageBuilder.createMessage(new byte[0], disc.getMessageHeaders()), null);
        assertThat(presence.watching(7L, "support-5")).isFalse();
    }

    @Test
    @DisplayName("거절된 구독은 '보고 있다' 로 적지 않는다")
    void rejectedNotTracked() {
        when(supportService.canSubscribe(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> handler.preSend(subscribe("/sub/chat/room/support-5", "support-5", 7L, null), null))
                .isInstanceOf(RuntimeException.class);
        assertThat(presence.watching(7L, "support-5")).isFalse();
    }
}
