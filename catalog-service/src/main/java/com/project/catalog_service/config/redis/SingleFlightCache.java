package com.project.catalog_service.config.redis;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code @Cacheable(sync = true)} 의 읽기를 <b>키마다 한 스레드만</b> 계산하게 하는 감싸개(파드 안에서) — 나머지 동작은 그대로 넘긴다.
 *
 * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5 · mixed {@code 160904Z}): {@code search_cache} 키 48개가 같은 초에 만료되자 20초 동안
 * 커넥션 대기 22 · 서버 p95 1.5초 — 만료된 키마다 들어온 요청이 모두 같은 검색을 DB 에서 다시 계산했다. Spring Data Redis 3.x 의
 * {@code RedisCache.get(key, loader)} 는 잠그는 일을 캐시 작성기에 맡기는데, 이 서비스의 작성기는 잠그지 않는 쪽
 * ({@code nonLockingRedisCacheWriter})이라 {@code sync = true} 를 붙여도 아무도 기다리지 않았다(2.x 의 {@code RedisCache} 는
 * {@code synchronized} 였다). 잠그는 작성기는 캐시 <b>이름 전체</b>를 Redis 잠금 하나로 묶어 다른 키의 쓰기까지 세우므로 쓰지 않는다.
 *
 * <p>잠금은 키마다 따로이고, 기다리던 스레드는 잠금을 얻은 뒤 캐시를 다시 읽는다 — 앞선 스레드가 채운 값을 가져가고 DB 에 가지 않는다.
 * 기다리는 동안 DB 커넥션은 쥐지 않는다(계산하는 스레드만 쥔다). 값이 null 이면 담지 않는다(null 을 허용하지 않는 캐시가 있다).
 * 쓰기 실패는 경고만 하고 계산한 값을 돌려준다 — 밖으로 던지면 Spring 의 오류 처리기가 읽기 실패로 보고 한 번 더 계산한다.
 *
 * <p>[JA] {@code @Cacheable(sync = true)} の読み取りをキーごとに一つのスレッドだけが計算するようにする(Pod 内)。Spring Data Redis 3.x
 *      はロックをキャッシュライターに任せ、このサービスのライターはロックしないため {@code sync = true} が何もしていなかった。
 */
@Slf4j
final class SingleFlightCache implements Cache {

    private final Cache delegate;
    private final ConcurrentHashMap<Object, ReentrantLock> locks = new ConcurrentHashMap<>();

    SingleFlightCache(Cache delegate) {
        this.delegate = delegate;
    }

    Cache delegate() {
        return delegate;
    }

    /** 지금 잡혀 있는 키 잠금 수 — 계산이 끝나면 지운다(검색 조합마다 잠금이 쌓이지 않는다). */
    int inFlight() {
        return locks.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper hit = delegate.get(key);
        if (hit != null) {
            return (T) hit.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // 기다리는 동안 앞선 스레드가 채웠으면 그 값을 쓴다
            hit = delegate.get(key);
            if (hit != null) {
                return (T) hit.get();
            }
            T value;
            try {
                value = valueLoader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }
            if (value != null) {
                try {
                    delegate.put(key, value);
                } catch (RuntimeException e) {
                    log.warn("캐시 쓰기 실패 — 응답은 그대로. cache={} ({})", getName(), e.getClass().getSimpleName());
                }
            }
            return value;
        } finally {
            lock.unlock();
            // 기다리는 스레드가 없을 때만 지운다 — 그 사이 새로 온 스레드가 지운 잠금을 잡아도 다시 읽기에서 값을 찾는다
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        return delegate.get(key);
    }

    @Override
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    @Nullable
    public CompletableFuture<?> retrieve(Object key) {
        return delegate.retrieve(key);
    }

    @Override
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return delegate.retrieve(key, valueLoader);
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        delegate.put(key, value);
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        return delegate.putIfAbsent(key, value);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return delegate.evictIfPresent(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean invalidate() {
        return delegate.invalidate();
    }
}
