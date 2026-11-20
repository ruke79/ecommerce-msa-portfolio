package com.project.catalog_service.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 필터 메뉴 계산의 파드 사이 맡기 — 같은 메뉴(캐시 이름 + 키)는 클러스터에서 한 파드만 계산한다(SearchFilterFacets).
 *
 * <p>🐛 2026-10-21 실습 8시간(mixed {@code 150403Z}, 상품 1,000만, docs/83 §2.8): 메뉴 값은 Redis 에 계산 시각과 함께 있어, 10분이 지나
 * 오래된 순간을 <b>모든 파드가 같이</b> 본다 — 파드마다 자기 실행기로 같은 DISTINCT(브랜드 22초 · 색상 7초 · 사이즈 11초)를 동시에 돌렸다
 * (PostgreSQL 로그: 같은 매개변수의 같은 질의가 백엔드 셋에서 같은 순간에 끝남). 셋이 함께 표를 훑는 동안 DB 가 느려져 home 까지 6초 —
 * 게이트웨이의 catalog 자리(파드마다 25)가 차서 1분 동안 {@code /fallback} 503 1,472건.
 *
 * <p>Redis 에 만료가 있는 키 하나({@code SET NX PX}) — 계산을 맡은 파드가 죽어도 {@link Redis#lease} 뒤에는 풀린다. 풀 때는 자기가 건 값일
 * 때만 지운다. 맡지 못한 파드는 옛 값을 그대로 내주고(없으면 메뉴 없이), 새 값은 맡은 파드가 캐시에 쓴다.
 *
 * <p>[JA] フィルターメニュー計算のポッド間の担当 — 同じメニュー(キャッシュ名 + キー)はクラスタで一つのポッドだけが計算する(SearchFilterFacets)。
 *
 * <p>[JA] 2026-10-21 実習 8 時間(mixed {@code 150403Z}、商品 1,000 万、docs/83 §2.8): メニューの値は Redis に計算時刻と一緒にあり、10 分が過ぎて
 * 古くなった瞬間を<b>全ポッドが同時に</b>見る — ポッドごとに自分の実行器で同じ DISTINCT(ブランド 22 秒 · 色 7 秒 · サイズ 11 秒)を同時に流した
 * (PostgreSQL ログ: 同じパラメーターの同じ照会がバックエンド三つで同じ瞬間に終了)。三つが一緒に表を走査する間 DB が遅くなり home まで 6 秒 —
 * ゲートウェイの catalog 枠(ポッドごとに 25)が埋まり、1 分間 {@code /fallback} 503 が 1,472 件。
 *
 * <p>[JA] Redis に期限付きのキー一つ({@code SET NX PX}) — 計算を担当したポッドが死んでも {@link Redis#lease} の後には解ける。解くときは自分が置いた値の
 * ときだけ消す。担当できなかったポッドは古い値をそのまま返し(なければメニューなし)、新しい値は担当したポッドがキャッシュに書く。
 */
public interface FacetRefreshClaim {

    /**
     * 이 메뉴의 계산을 맡았으면 토큰, 다른 파드가 맡고 있으면 null.
     *
     * @throws RuntimeException 맡기를 확인하지 못했다(Redis) — 부르는 쪽은 이 파드에서 계산한다(메뉴가 없는 것보다 낫다)
     *
     * <p>[JA] このメニューの計算を引き受けたらトークン、他の Pod が引き受けていれば null。
     * RuntimeException: 引き受けを確認できなかった(Redis) — 呼ぶ側はこの Pod で計算する(メニューがないよりましだ)。
     */
    String tryClaim(String flightKey);

    void release(String flightKey, String token);

    /** 한 파드 안에서만(테스트 · 로컬) — 늘 맡는다. */
    // [JA] 一つの Pod の中だけ(テスト・ローカル) — 常に引き受ける。
    FacetRefreshClaim LOCAL = new FacetRefreshClaim() {
        @Override
        public String tryClaim(String flightKey) {
            return "local";
        }

        @Override
        public void release(String flightKey, String token) {
        }
    };

    @Slf4j
    @Component
    class Redis implements FacetRefreshClaim {
        static final String PREFIX = "catalog:facets:refresh:";
        private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

        private final StringRedisTemplate redis;
        private final Duration lease;

        /** 맡는 시간 = 메뉴 질의의 시간 상한({@code catalog.facets.query-timeout-seconds}, FacetQueries) + 1분 — 그보다 오래 계산하는 일은 없다. */
        // [JA] 引き受ける時間 = メニュークエリの時間上限(catalog.facets.query-timeout-seconds、FacetQueries)+ 1 分 — それより長く計算することはない。
        public Redis(StringRedisTemplate redis, @Value("${catalog.facets.query-timeout-seconds:600}") int queryTimeoutSeconds) {
            this.redis = redis;
            this.lease = Duration.ofSeconds(Math.max(1, queryTimeoutSeconds) + 60L);
        }

        Duration lease() {
            return lease;
        }

        /** 키에는 요청의 카테고리 이름이 들어갈 수 있다 — 길이를 고정한다. */
        // [JA] キーにはリクエストのカテゴリ名が入りうる — 長さを固定する。
        static String redisKey(String flightKey) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(flightKey.getBytes(StandardCharsets.UTF_8));
                return PREFIX + HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String tryClaim(String flightKey) {
            String mine = UUID.randomUUID().toString();
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey(flightKey), mine, lease)) ? mine : null;
        }

        @Override
        public void release(String flightKey, String token) {
            try {
                redis.execute(RELEASE, List.of(redisKey(flightKey)), token);
            } catch (RuntimeException e) {
                log.warn("필터 메뉴 계산 맡기를 풀지 못했다 — {} 뒤에 저절로 풀린다 ({})", lease, e.getClass().getSimpleName());
            }
        }
    }
}
