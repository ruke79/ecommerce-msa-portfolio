package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 데모 채팅 상한 — 같은 창에서 상한까지 통과 · 넘으면 거절, Redis 실패는 통과(docs/100).
 * [JA] デモチャット上限 — 同じ窓で上限まで通過 · 超えたら拒否、Redis 失敗は通過。
 */
@DisplayName("데모 채팅 상한")
class DemoChatLimiterTest {

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 상한 3 — 세 번 통과, 네 번째 거절")
    void allowsUpToLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Map<String, Long> counts = new HashMap<>();
        when(ops.increment(anyString())).thenAnswer(inv -> counts.merge(inv.getArgument(0), 1L, Long::sum));
        DemoChatLimiter limiter = new DemoChatLimiter(redis, 3);

        assertThat(limiter.allow(7L)).isTrue();
        assertThat(limiter.allow(7L)).isTrue();
        assertThat(limiter.allow(7L)).isTrue();
        assertThat(limiter.allow(7L)).isFalse();
        assertThat(limiter.allow(8L)).as("다른 계정은 따로").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Redis 가 실패하면 막지 않는다")
    void redisFailureAllows() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(any())).thenThrow(new IllegalStateException("down"));
        assertThat(new DemoChatLimiter(redis, 3).allow(7L)).isTrue();
    }
}
