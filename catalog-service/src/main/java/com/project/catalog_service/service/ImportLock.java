package com.project.catalog_service.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 일괄 임포트의 파드 사이 잠금 — 한 번에 하나만 돈다(ProductImportService).
 *
 * <p>Redis 에 만료가 있는 키 하나({@code SET NX PX}). 파드가 잠금을 쥔 채 죽어도 {@link #LEASE} 뒤에는 풀린다. 풀 때는 자기가 건
 * 값일 때만 지운다(만료 뒤 다른 파드가 새로 잡은 잠금을 지우지 않게).
 *
 * <p>[JA] 一括インポートのポッド間ロック。Redis の期限付きキー一つ。解放は自分が置いた値のときだけ。
 */
public interface ImportLock {

    /** 잠금을 잡았으면 true. */
    boolean tryAcquire();

    void release();

    /** 한 파드 안에서만(테스트 · 로컬). */
    ImportLock LOCAL = new ImportLock() {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {
        }
    };

    /** 임포트 한 번의 상한 — 이보다 오래 걸리는 임포트는 없다(실측 수 분). 죽은 파드의 잠금도 이 뒤에 풀린다. */
    Duration LEASE = Duration.ofHours(2);

    @Slf4j
    @Component
    class Redis implements ImportLock {
        static final String KEY = "catalog:product-import:lock";
        private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

        private final StringRedisTemplate redis;
        private volatile String token;

        public Redis(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public boolean tryAcquire() {
            String mine = UUID.randomUUID().toString();
            Boolean ok = redis.opsForValue().setIfAbsent(KEY, mine, LEASE);
            if (Boolean.TRUE.equals(ok)) {
                token = mine;
                return true;
            }
            return false;
        }

        @Override
        public void release() {
            String mine = token;
            token = null;
            if (mine == null) {
                return;
            }
            try {
                redis.execute(RELEASE, java.util.List.of(KEY), mine);
            } catch (RuntimeException e) {
                log.warn("임포트 잠금을 풀지 못했다 — {} 뒤에 저절로 풀린다 ({})", LEASE, e.getClass().getSimpleName());
            }
        }
    }
}
