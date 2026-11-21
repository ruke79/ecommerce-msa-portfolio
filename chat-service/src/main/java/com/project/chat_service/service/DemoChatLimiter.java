package com.project.chat_service.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정의 채팅 보내기 상한(docs/100) — 계정마다 10분 창에 {@code chat.demo.max-messages}(기본 20)개. 고정 창 · Redis INCR 라
 * 파드가 여럿이어도 같은 수를 센다. Redis 가 실패하면 막지 않는다(채팅 전체를 멈추지 않는다).
 *
 * <p>[JA] 公開デモアカウントのチャット送信上限 — アカウントごとに 10 分の窓で既定 20 件。固定窓 · Redis INCR なので Pod が複数でも
 *      同じ数を数える。Redis が失敗したら止めない。
 */
@Slf4j
@Component
public class DemoChatLimiter {

    static final Duration WINDOW = Duration.ofMinutes(10);
    static final String KEY_PREFIX = "chat:demo-rate:";

    private final StringRedisTemplate redis;
    private final int maxMessages;

    @Autowired
    public DemoChatLimiter(StringRedisTemplate redis, @Value("${chat.demo.max-messages:20}") int maxMessages) {
        this.redis = redis;
        this.maxMessages = maxMessages;
    }

    /** @return 이번 메시지를 보내도 되는가 [JA] 今回のメッセージを送ってよいか */
    public boolean allow(Long userId) {
        long window = System.currentTimeMillis() / WINDOW.toMillis();
        String key = KEY_PREFIX + userId + ":" + window;
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redis.expire(key, WINDOW.plusMinutes(1));
            }
            return n == null || n <= maxMessages;
        } catch (RuntimeException e) {
            log.debug("데모 채팅 상한을 Redis 에서 세지 못해 통과시킨다: {}", e.getClass().getSimpleName());
            return true;
        }
    }
}
