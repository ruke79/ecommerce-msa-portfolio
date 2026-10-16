package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 임포트 잠금(Redis) — 만료가 있는 SET NX, 자기 값일 때만 푼다. */
@DisplayName("임포트 잠금 — Redis")
class ImportLockTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("🔴 잠금은 만료(2시간)가 있는 SET NX — 이미 있으면 잡지 못한다, 잡지 못했으면 풀지도 않는다")
    void setNxWithLease() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(eq(ImportLock.Redis.KEY), anyString(), eq(ImportLock.LEASE))).thenReturn(true, false);
        ImportLock.Redis a = new ImportLock.Redis(redis);
        ImportLock.Redis b = new ImportLock.Redis(redis);

        assertThat(a.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();

        b.release();
        verify(redis, never()).execute(any(), any(java.util.List.class), any(Object[].class));
        a.release();
        verify(redis).execute(any(), eq(java.util.List.of(ImportLock.Redis.KEY)), anyString());
    }
}
