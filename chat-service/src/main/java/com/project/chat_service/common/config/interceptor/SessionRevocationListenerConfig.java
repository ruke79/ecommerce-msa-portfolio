package com.project.chat_service.common.config.interceptor;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.project.common.security.SessionRevocation;

import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 해제 알림을 받아 그 사용자의 채팅 연결을 닫는다(user-service docs/68 §9).
 *
 * <p>user-service 가 "모든 기기에서 로그아웃" · 리프레시 토큰 재사용 감지 때 {@link SessionRevocation#CHANNEL} 로 사용자 id 를 보낸다.
 * 모르는 값이면 무시한다 — 알림 하나 때문에 리스너 컨테이너를 죽이지 않는다.
 */
// [JA] ログイン解除の通知を受け、そのユーザーのチャット接続を閉じる。
@Slf4j
@Configuration
public class SessionRevocationListenerConfig {

    @Bean
    RedisMessageListenerContainer sessionRevocationListener(RedisConnectionFactory connectionFactory,
            LiveSessionRegistry liveSessions) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                liveSessions.closeUser(Long.parseLong(
                        new String(message.getBody(), StandardCharsets.UTF_8).replace("\"", "").trim()));
            } catch (RuntimeException e) {
                log.warn("로그인 해제 알림을 처리하지 못했다: {}", e.getClass().getSimpleName());
            }
        }, new ChannelTopic(SessionRevocation.CHANNEL));
        return container;
    }
}
