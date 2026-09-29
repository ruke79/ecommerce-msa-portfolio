package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * 채팅 연결을 토큰 수명 · 로그인 해제에 묶기(user-service docs/68 §9).
 *
 * <p>🐛 예전: CONNECT 뒤 끊길 때까지 살아 있었다 — 토큰이 만료돼도, 로그인이 끊겨도 방의 메시지를 계속 받았다.
 */
@DisplayName("채팅 연결 — 토큰 만료 · 로그인 해제에 닫는다")
class LiveSessionRegistryTest {

    private StringRedisTemplate redis;
    private LiveSessionRegistry registry;
    private WebSocketHandler connected;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
        registry = new LiveSessionRegistry(redis);
        connected = registry.decorate(mock(WebSocketHandler.class));
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    private WebSocketSession open(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        connected.afterConnectionEstablished(s);
        return s;
    }

    @Test
    @DisplayName("🔴 토큰 만료에 4401 로 닫는다 — 프론트는 갱신한 토큰으로 다시 붙는다")
    void closesAtTokenExpiry() throws Exception {
        WebSocketSession s = open("s1");

        registry.bind("s1", 7L, System.currentTimeMillis() + 200);

        verify(s, timeout(2_000)).close(new CloseStatus(4401, "token expired"));
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("🔴 로그인 해제 — 그 사용자의 연결만 모두 닫는다")
    void revocationClosesOnlyThatUser() throws Exception {
        WebSocketSession phone = open("phone");
        WebSocketSession laptop = open("laptop");
        WebSocketSession someoneElse = open("other");
        long later = System.currentTimeMillis() + 600_000;
        registry.bind("phone", 7L, later);
        registry.bind("laptop", 7L, later);
        registry.bind("other", 8L, later);

        assertThat(registry.closeUser(7L)).isEqualTo(2);

        verify(phone).close(new CloseStatus(4401, "session revoked"));
        verify(laptop).close(new CloseStatus(4401, "session revoked"));
        verify(someoneElse, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("스스로 끊긴 연결은 만료 예약도 지운다 — 닫힌 세션을 다시 닫으려 들지 않는다")
    void closedSessionForgetsItsDeadline() throws Exception {
        WebSocketSession s = open("s1");
        registry.bind("s1", 7L, System.currentTimeMillis() + 200);

        connected.afterConnectionClosed(s, CloseStatus.NORMAL);
        Thread.sleep(400);

        verify(s, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("해제 시각 이전에 발급된 토큰은 안 되고, 이후 것은 된다 · 표시가 없으면 된다")
    void cutoffDecidesReconnect() {
        when(redis.opsForValue().get("auth:cutoff:7")).thenReturn("1000");

        assertThat(registry.allowed(7L, 999)).isFalse();
        assertThat(registry.allowed(7L, 1000)).isTrue();
        assertThat(registry.allowed(8L, 1)).isTrue();
    }

    @Test
    @DisplayName("Redis 를 읽지 못해도 채팅을 멈추지 않는다 — 만료 묶기가 여전히 상한을 건다")
    void redisFailureDoesNotBlockChat() {
        when(redis.opsForValue().get(any())).thenThrow(new RuntimeException("down"));

        assertThat(registry.allowed(7L, 1)).isTrue();
    }
}
