package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.service.DemoChatLimiter;

/**
 * 공개 데모 계정의 채팅 보내기 상한(docs/100) — 데모 세션이 상한을 넘으면 그 SEND 만 버린다(null — 연결은 그대로), 보통 세션은 세지 않는다.
 * CONNECT 가 토큰의 demo 클레임으로 세션에 표시를 남긴다.
 * [JA] 公開デモアカウントのチャット送信上限 — デモのセッションが上限を超えたらその SEND だけ捨てる。通常セッションは数えない。
 */
@DisplayName("StompHandler — 데모 계정 보내기 상한")
class StompHandlerDemoLimitTest {

    TokenHandler tokenHandler = mock(TokenHandler.class);
    DemoChatLimiter limiter = mock(DemoChatLimiter.class);
    StompHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StompHandler();
        ReflectionTestUtils.setField(handler, "tokenHandler", tokenHandler);
        ReflectionTestUtils.setField(handler, "demoChatLimiter", limiter);
        LiveSessionRegistry live = mock(LiveSessionRegistry.class);
        when(live.allowed(any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(handler, "liveSessions", live);
    }

    private static Message<byte[]> send(boolean demo) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("sess-1");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", 7L);
        attrs.put(StompHandler.SESSION_ATTR_EXPIRES_AT, System.currentTimeMillis() + 600_000L);
        if (demo) {
            attrs.put(StompHandler.SESSION_ATTR_DEMO, Boolean.TRUE);
        }
        accessor.setSessionAttributes(attrs);
        accessor.setDestination("/pub/chat/message");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("🔴 데모 세션이 상한을 넘으면 그 메시지만 버린다(null), 안 넘으면 통과")
    void demoOverLimitIsDropped() {
        when(limiter.allow(7L)).thenReturn(false);
        assertThat(handler.preSend(send(true), null)).isNull();
        when(limiter.allow(7L)).thenReturn(true);
        assertThat(handler.preSend(send(true), null)).isNotNull();
    }

    @Test
    @DisplayName("보통 세션은 세지도 않는다")
    void regularSessionNotCounted() {
        assertThat(handler.preSend(send(false), null)).isNotNull();
        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("🔴 CONNECT — 토큰이 데모면 세션에 표시, 아니면 없음")
    void connectMarksDemoSession() {
        when(tokenHandler.verifyToken("t")).thenReturn(true);
        when(tokenHandler.getUserId("t")).thenReturn(7L);
        when(tokenHandler.getExpiresAtEpochMs("t")).thenReturn(System.currentTimeMillis() + 600_000L);
        for (boolean demo : new boolean[] { true, false }) {
            when(tokenHandler.isDemo("t")).thenReturn(demo);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.setSessionId("sess-" + demo);
            accessor.setSessionAttributes(new HashMap<>());
            accessor.setNativeHeader("Authorization", "t");
            accessor.setLeaveMutable(true);
            Message<byte[]> m = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            handler.preSend(m, null);
            assertThat(StompHeaderAccessor.wrap(m).getSessionAttributes().get(StompHandler.SESSION_ATTR_DEMO))
                    .isEqualTo(demo ? Boolean.TRUE : null);
        }
    }
}
