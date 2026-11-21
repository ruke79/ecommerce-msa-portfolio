package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 재색인 잠금(Redis) — 파드 수와 무관하게 한 번에 하나.
 *
 * <p>🐛 2026-11-20 실습: 중복 방지가 컨트롤러의 {@code AtomicBoolean} 이라 레플리카 2개에서 다른 파드로 간 두 번째
 * 요청이 통과했고, 재색인 잡 둘이 21시간 함께 돌았다(싱글턴 리더를 나눠 읽는다).
 *
 * <p>[JA] 再索引ロック(Redis) — Pod 数に関係なく一度に一つ。2026-11-20 の実習: 重複防止がコントローラーの AtomicBoolean で、
 * レプリカ 2 つでは別の Pod に行った二つ目のリクエストが通り、再索引ジョブ二つが 21 時間一緒に動いた。
 */
@DisplayName("재색인 잠금 — Redis(파드 사이)")
class ReindexLockTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("🔴 두 파드의 잠금은 **같은 키** — 둘째는 잡지 못한다, 잡지 못했으면 풀지도 않는다(남의 잠금을 지우지 않게)")
    void secondPodCannotAcquire() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq(ReindexLock.Redis.KEY), anyString(), any(Duration.class))).thenReturn(true, false);
        // 서로 다른 파드의 빈 — 공유되는 상태는 Redis 의 키뿐이다(AtomicBoolean 이면 둘 다 true 였다)
        // [JA] 別々の Pod の Bean — 共有される状態は Redis のキーだけ(AtomicBoolean なら両方 true だった)
        ReindexLock.Redis podA = new ReindexLock.Redis(redis, Duration.ofHours(12));
        ReindexLock.Redis podB = new ReindexLock.Redis(redis, Duration.ofHours(12));

        assertThat(podA.tryAcquire()).isTrue();
        assertThat(podB.tryAcquire()).isFalse();

        podB.release();
        verify(redis, never()).execute(any(), any(List.class), any(Object[].class));
        podA.release();
        verify(redis).execute(any(), eq(List.of(ReindexLock.Redis.KEY)), anyString());
    }

    @Test
    @DisplayName("🔴 임포트 잠금과 다른 키 · 리스는 설정으로(기본 12시간) — 재색인은 1,000만 상품에서 몇 시간이다")
    void ownKeyAndConfigurableLease() {
        assertThat(ReindexLock.Redis.KEY).isEqualTo("catalog:product-reindex:lock")
                .isNotEqualTo(ImportLock.Redis.KEY);

        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        new ReindexLock.Redis(redis, Duration.ofHours(30)).tryAcquire();
        verify(ops).setIfAbsent(eq(ReindexLock.Redis.KEY), anyString(), eq(Duration.ofHours(30)));
    }

    @Test
    @DisplayName("🔴 LOCAL 은 늘 잡는다 — 시험 · 단일 파드용")
    void localAlwaysAcquires() {
        assertThat(ReindexLock.LOCAL.tryAcquire()).isTrue();
        ReindexLock.LOCAL.release();
    }
}
