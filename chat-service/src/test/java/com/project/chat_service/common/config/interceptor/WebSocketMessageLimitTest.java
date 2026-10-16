package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.project.chat_service.dto.MessageDto;

/**
 * 들어오는 STOMP 메시지 하나의 상한은 64KB — 본문 상한(1000자)에 넉넉하고 그 이상은 필요 없다.
 *
 * <p>🐛 예전: 160 × 64KB = 10MB 였다 — 10MB 짜리 메시지가 몽고에 저장되고 방 전체에 방송될 수 있었다.
 *
 * <p>[JA] 受信する STOMP メッセージ一つの上限は 64KB。以前は 10MB だった。
 */
@DisplayName("WebSocket 전송 — 메시지 상한")
class WebSocketMessageLimitTest {

    @Test
    @DisplayName("🔴 메시지 상한은 64KB — 본문 1000자(UTF-8 최대 4바이트)보다 크다")
    void messageSizeLimit() {
        WebSocketTransportRegistration registration = new WebSocketTransportRegistration();
        new WebSocketConfiguration(mock(LiveSessionRegistry.class)).configureWebSocketTransport(registration);

        Integer limit = (Integer) ReflectionTestUtils.getField(registration, "messageSizeLimit");
        assertThat(limit).isEqualTo(64 * 1024);
        assertThat(limit).isGreaterThan(MessageDto.MAX_CONTENT_LENGTH * 4 + 4096);
    }
}
