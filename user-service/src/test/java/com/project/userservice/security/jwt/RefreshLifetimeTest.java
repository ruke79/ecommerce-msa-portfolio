package com.project.userservice.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.redis.RefreshTokenRepository;

import jakarta.servlet.http.Cookie;

/**
 * 🐛 리프레시 토큰의 수명이 단위 착오로 사실상 무한이었다 (docs/57 §2, docs/38 §2.1 #6).
 *
 * <ul>
 *   <li>Redis: {@code expire(키, 지금 시각 + 기간, ms)} — 기간 자리에 epoch 시각이 들어가 TTL 이 약 56년.</li>
 *   <li>쿠키: {@code Max-Age}(초)에 {@code jwtRefreshExpirationMs}(밀리초) — 14일짜리 쿠키가 약 38년.</li>
 * </ul>
 */
@DisplayName("리프레시 토큰 수명 — 쿠키 Max-Age 와 Redis TTL")
class RefreshLifetimeTest {

    private static final int FOURTEEN_DAYS_MS = 1_209_600_000;

    @Test
    @DisplayName("🔴 리프레시 쿠키의 Max-Age 는 14일(초) — 밀리초를 초로 쓰지 않는다")
    void cookieMaxAgeInSeconds() {
        JwtUtils jwt = new JwtUtils(null);
        ReflectionTestUtils.setField(jwt, "jwtRefreshExpirationMs", FOURTEEN_DAYS_MS);

        Cookie cookie = jwt.createCookie("REFRESH", "token", jwt.refreshTokenLifetime());

        assertThat(cookie.getMaxAge()).isEqualTo(14 * 24 * 60 * 60);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
    }

    @Test
    @DisplayName("지우는 쿠키는 Max-Age 0")
    void clearCookie() {
        assertThat(new JwtUtils(null).createCookie("REFRESH", null, Duration.ZERO).getMaxAge()).isZero();
    }

    @Test
    @DisplayName("🔴 Redis 에는 값과 TTL(14일)을 한 명령으로 넣는다 — epoch 시각을 기간으로 쓰지 않는다")
    @SuppressWarnings("unchecked")
    void redisTtlIsTheLifetime() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(any(), any(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        RefreshTokenRepository repo = new RefreshTokenRepository(redis);
        ReflectionTestUtils.setField(repo, "jwtRefreshExpirationMs", FOURTEEN_DAYS_MS);

        repo.save(new RefreshToken("sess-1", "7", "token"));

        // 값은 토큰 원문이 아니라 SHA-256 해시다(docs/68 §10)
        verify(ops).set(eq("sess-1"), eq(com.project.userservice.repository.redis.RefreshTokenRepository.digest("token")), eq(Duration.ofDays(14)));
        verify(redis, never()).expire(any(), anyLong(), any(TimeUnit.class));
    }
}
