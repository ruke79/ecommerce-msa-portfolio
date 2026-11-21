package com.project.catalog_service.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 전체 재색인의 파드 사이 잠금 — 한 번에 하나만 돈다(SearchController {@code POST /api/products/sync}).
 *
 * <p>🐛 2026-11-20 실습: 중복 실행 방지가 컨트롤러의 {@code AtomicBoolean} 하나였다. catalog 레플리카가 2개라
 * 게이트웨이가 **다른 파드**로 보내면 그냥 통과한다 — 재색인 잡 둘이 21시간 동안 함께 돌았다. 두 잡은
 * {@code ElasticsearchBatchConfig} 의 리더 빈(모두 {@code @StepScope} 없는 싱글턴)을 **같은 인스턴스로** 나눠 쓰므로
 * 읽기 위치가 뒤엉킨다(그 컨트롤러 주석이 이미 적어 둔 위험이다). 같은 결함이 일괄 임포트에서는
 * {@link ImportLock}(Redis)으로 이미 고쳐져 있었는데 재색인만 남아 있었다.
 *
 * <p>구조는 {@link ImportLock} 과 같다 — Redis 에 만료가 있는 키 하나({@code SET NX PX}), 풀 때는 자기가 건 값일 때만.
 * 다른 점은 <b>리스가 길다</b>: 재색인은 상품 1,000만 · SKU 1,700만 · 사이즈 3,000만에서 몇 시간을 돈다(실습 실측).
 * 파드가 잠금을 쥔 채 죽으면 그 시간만큼 다음 재색인이 막히므로, 기다리지 않으려면 그 키를 지운다:
 * {@code redis-cli DEL catalog:product-reindex:lock}.
 *
 * <p>[JA] 全体再索引のポッド間ロック。以前は AtomicBoolean だけで、レプリカ 2 個のため別ポッドに来た要求が通り、
 *      ジョブが二つ同時に走ってシングルトンのリーダーを共有していた。構造は ImportLock と同じ、リースだけ長い。
 */
public interface ReindexLock {

    /** 잠금을 잡았으면 true. */
    // [JA] ロックを取れたら true。
    boolean tryAcquire();

    void release();

    /** 한 파드 안에서만(테스트 · 로컬). */
    // [JA] 一つの Pod の中だけ(テスト · ローカル)。
    ReindexLock LOCAL = new ReindexLock() {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {
        }
    };

    @Slf4j
    @Component
    class Redis implements ReindexLock {
        static final String KEY = "catalog:product-reindex:lock";
        private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

        private final StringRedisTemplate redis;
        private final Duration lease;
        private volatile String token;

        public Redis(StringRedisTemplate redis,
                @Value("${catalog.reindex.lock-lease:PT12H}") Duration lease) {
            this.redis = redis;
            this.lease = lease;
        }

        @Override
        public boolean tryAcquire() {
            String mine = UUID.randomUUID().toString();
            Boolean ok = redis.opsForValue().setIfAbsent(KEY, mine, lease);
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
                redis.execute(RELEASE, List.of(KEY), mine);
            } catch (RuntimeException e) {
                log.warn("재색인 잠금을 풀지 못했다 — {} 뒤에 저절로 풀린다 ({})", lease, e.getClass().getSimpleName());
            }
        }
    }
}
