package com.project.userservice.security.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 로그인 비밀번호 권고(docs/57 §22). 🐛 예전에는 가입 · 변경 · 재설정에서만 유출을 봐, 그 뒤 샌 비밀번호를 쓰는 사람은 알 길이 없었다.
 * [JA] ログイン時のパスワード勧告。以前は登録 · 変更 · 再設定でだけ見ていた。
 */
class LoginPasswordAdvisorTest {

    private static final String PASSWORD = "correct horse battery staple 42";
    private static final String SUFFIX = BreachedPasswordChecker.sha1Hex(PASSWORD).substring(5);

    private final AtomicInteger calls = new AtomicInteger();
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private LoginPasswordAdvisor advisor(BreachedPasswordChecker.RangeFetcher fetcher) {
        when(redis.opsForValue()).thenReturn(ops);
        BreachedPasswordChecker checker = new BreachedPasswordChecker(true, prefix -> {
            calls.incrementAndGet();
            return fetcher.fetch(prefix);
        }, new SimpleMeterRegistry());
        return new LoginPasswordAdvisor(checker, redis, new SimpleMeterRegistry(), true, Duration.ofDays(7));
    }

    @Test
    @DisplayName("🔴 유출 목록에 있으면 권하고, 기억하지 않는다 — 바꿀 때까지 로그인마다")
    void breachedPasswordIsAdvisedEveryTime() {
        LoginPasswordAdvisor a = advisor(prefix -> SUFFIX + ":12\r\n");
        assertThat(a.adviceFor(7L, PASSWORD)).isEqualTo(LoginPasswordAdvisor.ADVICE_BREACHED);
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("흔한 비밀번호도 권한다(로컬 목록)")
    void commonPasswordIsAdvised() {
        assertThat(advisor(prefix -> "").adviceFor(7L, "password")).isEqualTo(LoginPasswordAdvisor.ADVICE_BREACHED);
    }

    @Test
    @DisplayName("괜찮으면 7일 기억하고, 기억이 있으면 외부 API 를 부르지 않는다")
    void okIsRememberedAndSkipsTheApi() {
        LoginPasswordAdvisor a = advisor(prefix -> "");
        assertThat(a.adviceFor(7L, PASSWORD)).isNull();
        verify(ops).set("auth:pw-ok:7", "1", Duration.ofDays(7));

        when(redis.hasKey("auth:pw-ok:7")).thenReturn(true);
        assertThat(a.adviceFor(7L, PASSWORD)).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("확인하지 못하면(API 장애) 권하지 않고 1시간만 기억한다 · Redis 가 죽어도 로그인을 바꾸지 않는다")
    void failuresNeverChangeTheLogin() {
        LoginPasswordAdvisor a = advisor(prefix -> { throw new IOException("down"); });
        assertThat(a.adviceFor(7L, PASSWORD)).isNull();
        verify(ops).set(eq("auth:pw-ok:7"), eq("1"), eq(Duration.ofHours(1)));

        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertThat(advisor(prefix -> SUFFIX + ":3").adviceFor(8L, PASSWORD)).isEqualTo(LoginPasswordAdvisor.ADVICE_BREACHED);
    }
}
