package com.project.userservice.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인한 상태에서 <b>현재 비밀번호</b>를 묻는 곳(비밀번호 변경 · 탈퇴)의 시도 제한 — 사용자마다 15분에 5번.
 *
 * <p>🐛 예전에는 제한이 없었다 — 세션을 가진 사람(공용 PC · 탈취한 쿠키)이 이 엔드포인트를 BCrypt 오라클로 써서 비밀번호를 대입할 수
 * 있었다(막는 것은 게이트웨이의 IP 속도 제한뿐). 시도는 비교 <b>전에</b> 센다(Lua 한 번) — 동시에 보내도 한도를 넘지 못한다.
 *
 * <p>[JA] ログイン中に現在のパスワードを尋ねる箇所(パスワード変更 · 退会)の試行制限 — ユーザーごとに 15 分で 5 回。以前は制限がなかった。
 */
@Component
public class CurrentPasswordGuard {

    static final String KEY = "auth:current-password:";

    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int max;
    private final long windowMs;

    public CurrentPasswordGuard(StringRedisTemplate redis,
            @Value("${auth.current-password.max-attempts:5}") int max,
            @Value("${auth.current-password.window-ms:900000}") long windowMs) {
        this.redis = redis;
        this.max = max;
        this.windowMs = windowMs;
    }

    /** 비교 전에 부른다 — 한도를 넘었으면 {@link TooManyAttemptsException}. */
    public void reserve(Long userId) {
        Long n = redis.execute(RESERVE, List.of(KEY + userId), String.valueOf(windowMs));
        if (n != null && n > max) {
            throw new TooManyAttemptsException();
        }
    }

    /** 맞았다 — 이 창의 시도를 지운다. */
    public void clear(Long userId) {
        redis.delete(KEY + userId);
    }

    /** 현재 비밀번호를 너무 많이 틀렸다 — 컨트롤러가 429 로. */
    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException() {
            super("비밀번호를 너무 많이 틀렸습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
