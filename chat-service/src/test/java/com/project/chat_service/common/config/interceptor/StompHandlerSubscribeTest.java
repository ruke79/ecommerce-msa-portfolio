package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

/**
 * 구독 프레임은 <b>기록 실패로 죽지 않는다.</b>
 *
 * <h3>🐛 회귀 대상</h3>
 *
 * <p>{@code preSend} 가 참여자 등록 중의 예외를 그대로 던졌다. 인바운드 채널에서 던진
 * 예외는 STOMP ERROR 프레임이 되고, 화면에는 내부 사정을 말하는
 * {@code Failed to send message to ExecutorSubscribableChannel[clientInboundChannel]} 만
 * 보인다. 그 방은 <b>영영 못 들어간다.</b>
 *
 * <p>그런데 참여자 등록은 <b>기록</b>이지 권한 검사가 아니다 — 구독 자체는 누구에게도 막혀
 * 있지 않다. 기록에 실패하면 서버 로그에 남기고 구독은 통과시킨다. 인증 실패
 * (토큰 없음·roomId 없음)는 <b>그대로 거절</b>한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StompHandler — SUBSCRIBE")
class StompHandlerSubscribeTest {

    @Mock TokenHandler tokenHandler;
    @Mock ChatService chatService;

    StompHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StompHandler();
        ReflectionTestUtils.setField(handler, "tokenHandler", tokenHandler);
        ReflectionTestUtils.setField(handler, "chatService", chatService);
        LiveSessionRegistry live = org.mockito.Mockito.mock(LiveSessionRegistry.class);
        when(live.allowed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(handler, "liveSessions", live);
        ReflectionTestUtils.setField(handler, "supportService", org.mockito.Mockito.mock(
                com.project.chat_service.service.SupportService.class));
        ReflectionTestUtils.setField(handler, "supportPresence", new com.project.chat_service.service.SupportPresence());
    }

    private Message<byte[]> subscribe(String roomId, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(sessionAttributes);
        // 실제 클라이언트(ChatArea)처럼 구독 주소와 roomId 헤더를 함께 싣는다 — 권한은 주소로 판정한다(docs/58).
        accessor.setDestination("/sub/chat/room/" + (roomId != null ? roomId : "room-1"));
        if (roomId != null) {
            accessor.setNativeHeader("roomId", roomId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Map<String, Object> session(Object userId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() + 600_000L);
        return attrs;
    }

    /** 🔴 핵심 — 등록이 터져도 구독은 살아서 지나간다. */
    @Test
    @DisplayName("🔴 참여자 등록이 실패해도 구독 프레임은 통과한다")
    void bookkeepingFailureDoesNotKillTheSubscription() {
        doThrow(new IllegalStateException("채팅방을 찾을 수 없습니다: room-1"))
                .when(chatService).connect(any(), any(), any());

        Message<?> result = handler.preSend(subscribe("room-1", session(7L)), null);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("CONNECT 때 담아 둔 userId 가 신원이다 — 참여자 등록에 그것만 넘긴다")
    void userIdFromSessionIsTheIdentity() {
        handler.preSend(subscribe("room-1", session(7L)), null);

        verify(chatService).connect(eq("room-1"), eq("sess-1"), eq(7L));
    }

    /** 신원은 uid 하나다(docs/48 §6-⑥) — 예전처럼 이메일만 있는 세션은 신원이 없는 것이다. */
    @Test
    @DisplayName("🔒 userId 가 없는 세션은 거절한다 — 이메일만으로는 들어오지 못한다")
    void emailOnlySessionIsRejected() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("email", "someone@example.com");

        assertThatThrownBy(() -> handler.preSend(subscribe("room-1", legacy), null))
                .isInstanceOf(RuntimeException.class);
    }

    /** 인증은 여전히 막는다 — 기록 실패와 신원 없음은 다른 문제다. */
    @Test
    @DisplayName("roomId 가 없으면 거절한다")
    void missingRoomIdIsRejected() {
        assertThatThrownBy(() -> handler.preSend(subscribe(null, session(7L)), null))
                .isInstanceOf(RuntimeException.class);
    }

    /** 🔒 CONNECT — uid 없는 토큰은 서명이 맞아도 받지 않는다. 세션에 이메일도 담지 않는다. */
    @Test
    @DisplayName("🔒 CONNECT: uid 없는 토큰은 거절, 세션에는 userId 만 담는다")
    void connectStoresOnlyUserId() {
        when(tokenHandler.verifyToken("tok")).thenReturn(true);

        when(tokenHandler.getUserId("tok")).thenReturn(null);
        assertThatThrownBy(() -> handler.preSend(connect("tok"), null)).isInstanceOf(RuntimeException.class);

        when(tokenHandler.getUserId("tok")).thenReturn(7L);
        Message<byte[]> ok = connect("tok");
        handler.preSend(ok, null);
        Map<String, Object> attrs = StompHeaderAccessor.wrap(ok).getSessionAttributes();
        assertThat(attrs).containsEntry("userId", 7L).doesNotContainKey("email");
    }

    private Message<byte[]> connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader("Authorization", token);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("세션에 검증된 신원이 없으면 거절한다")
    void missingIdentityIsRejected() {
        assertThatThrownBy(() -> handler.preSend(subscribe("room-1", new HashMap<>()), null))
                .isInstanceOf(RuntimeException.class);
    }

    // ── 연결을 토큰 수명 · 로그인 해제에 묶기(user-service docs/68 §9) ──────────────────

    @Test
    @DisplayName("🔴 CONNECT 는 토큰 만료에 닫기를 예약한다 — 예전에는 끊길 때까지 살아 있었다")
    void connectBindsTheSessionToTokenExpiry() {
        LiveSessionRegistry live = (LiveSessionRegistry) ReflectionTestUtils.getField(handler, "liveSessions");
        when(tokenHandler.verifyToken("tok")).thenReturn(true);
        when(tokenHandler.getUserId("tok")).thenReturn(7L);
        when(tokenHandler.getExpiresAtEpochMs("tok")).thenReturn(1_900_000_000_000L);

        Message<byte[]> ok = connect("tok");
        handler.preSend(ok, null);

        verify(live).bind("sess-1", 7L, 1_900_000_000_000L);
        assertThat(StompHeaderAccessor.wrap(ok).getSessionAttributes())
                .containsEntry(StompHandler.SESSION_ATTR_EXPIRES_AT, 1_900_000_000_000L);
    }

    @Test
    @DisplayName("🔴 로그인 해제 이전에 발급된 토큰의 CONNECT 는 거절한다 — 닫아도 옛 토큰으로 곧장 다시 붙으면 소용없다")
    void connectWithTokenIssuedBeforeRevocationIsRejected() {
        LiveSessionRegistry live = (LiveSessionRegistry) ReflectionTestUtils.getField(handler, "liveSessions");
        when(tokenHandler.verifyToken("old")).thenReturn(true);
        when(tokenHandler.getUserId("old")).thenReturn(7L);
        when(tokenHandler.getIssuedAtEpochSec("old")).thenReturn(1_000L);
        when(live.allowed(7L, 1_000L)).thenReturn(false);

        assertThatThrownBy(() -> handler.preSend(connect("old"), null)).isInstanceOf(RuntimeException.class);
        verify(live, org.mockito.Mockito.never()).bind(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("토큰이 만료된 세션의 SUBSCRIBE 는 거절한다 — 닫기 예약이 늦어도 두 번째 겹이 막는다")
    void subscribeAfterExpiryIsRejected() {
        Map<String, Object> attrs = session(7L);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() - 1);

        assertThatThrownBy(() -> handler.preSend(subscribe("room-1", attrs), null))
                .isInstanceOf(RuntimeException.class);
    }
}
