package com.project.userservice.security;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * "이 사용자가 지금 어느 세션으로 로그인해 있는가" 를 기억한다 — 다중 로그인 차단의 근거다.
 *
 * <p>🐛 <b>이름은 Redis 인데 실제로는 인메모리 {@code HashMap} 이었다.</b> 그래서 두 가지가
 * 동시에 틀렸다. user-service 는 {@code replicas: 2} 로 뜬다:
 *
 * <ul>
 *   <li><b>거짓 양성.</b> 항목을 지우는 곳이 <b>명시적 로그아웃 하나뿐</b>이었다. 브라우저를
 *       닫거나, 토큰이 만료되거나, 비밀번호를 재설정하고 다시 로그인하면 옛 기록이 그대로
 *       남아 <b>혼자 쓰는데도 "다중 로그인" 이라고 경고</b>했다. 실제로 그렇게 보고됐다.</li>
 *   <li><b>거짓 음성.</b> 맵이 파드마다 따로라, 두 번째 로그인이 <b>다른 파드로 가면 기록이
 *       없어</b> 차단이 아예 동작하지 않는다. 즉 막으려던 것을 대략 절반만 막았다.</li>
 * </ul>
 *
 * <p>Redis 로 옮기면 둘 다 사라진다. 레플리카가 같은 저장소를 보고, <b>TTL 이 있으므로
 * 기록이 스스로 만료된다</b> — 지우는 코드가 빠져도 옛 기록이 영원히 남지 않는다.
 *
 * <p>TTL 은 리프레시 토큰 수명과 맞춘다. 그보다 길면 이미 못 쓰는 세션을 살아 있다고 보고,
 * 짧으면 살아 있는 세션을 놓친다.
 *
 * <p>⚠️ 이 기록은 <b>판단의 근거일 뿐 권한이 아니다.</b> 세션이 실제로 유효한지는 리프레시
 * 토큰(Redis)과 {@code User.passwordChangedAt} 이 정한다. 여기 항목이 남아 있어도 그 토큰이
 * 없으면 그 세션은 이미 죽은 것이다.
 */
@Slf4j
@Component
public class RedisSessionManager {

    /** 키 접두사. 리프레시 토큰은 sessionId 를 그대로 키로 쓰므로 섞이지 않게 붙인다. */
    private static final String KEY_PREFIX = "login-session:";

    private final StringRedisTemplate redisTemplate;

    private final long sessionTtlMs;

    public RedisSessionManager(StringRedisTemplate redisTemplate,
            @Value("${spring.app.jwtRefreshExpirationMs}") long sessionTtlMs) {
        this.redisTemplate = redisTemplate;
        this.sessionTtlMs = sessionTtlMs;
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }

    public void addSession(String userId, String sessionId) {
        redisTemplate.opsForValue().set(key(userId), sessionId, sessionTtlMs, TimeUnit.MILLISECONDS);
    }

    public void removeSession(String userId) {
        redisTemplate.delete(key(userId));
    }

    public boolean hasSession(String userId) {
        return getSession(userId) != null;
    }

    /** @return 기억된 세션 id, 없거나 만료됐으면 null */
    public String getSession(String userId) {
        return redisTemplate.opsForValue().get(key(userId));
    }

    public boolean isSessionActive(String userId, String sessionId) {
        return Objects.equals(getSession(userId), sessionId);
    }
}
