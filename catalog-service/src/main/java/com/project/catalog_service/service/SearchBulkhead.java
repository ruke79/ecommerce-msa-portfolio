package com.project.catalog_service.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색의 DB 작업 격벽 — 파드 안에서 동시에 DB 를 쓰는 검색 수에 상한을 두고, 자리를 얻은 뒤에야 트랜잭션(커넥션)을 연다.
 *
 * <ul>
 *   <li>검색 전체 {@code catalog.search.bulkhead.max-concurrent}(기본 3) — 풀(파드마다 5)의 나머지는 home · banners · 상품 · 장바구니 쪽 몫.</li>
 *   <li>그 안에서 가격 · 색상 · 사이즈 필터 검색 {@code catalog.search.bulkhead.max-concurrent-filtered}(기본 2) — 필터 없는 검색이 한 자리는 늘 쓴다.</li>
 *   <li>자리를 {@code catalog.search.bulkhead.wait}(기본 200ms)까지만 기다리고, 못 얻으면 커넥션을 건드리지 않은 채 {@link Busy}(503) —
 *       캐시가 맞는 검색({@code search_cache})은 여기까지 오지 않는다.</li>
 *   <li>트랜잭션은 읽기 전용 · 시간 상한 {@code catalog.search.query-timeout-seconds}(기본 5초 — 넘으면 DB 에서 취소, SearchController 가 503).</li>
 *   <li>그 커넥션의 소켓 읽기도 상한 + {@code catalog.search.network-timeout-grace}(기본 5초)까지만 기다린다 — 커넥션이 풀로 돌아가면
 *       Hikari 가 원래 값으로 되돌린다.</li>
 * </ul>
 *
 * <p>🐛 2026-10-20 실습(상품 1,000만 · 필터 검색 비율 1, docs/83 §2.8): 시간 상한으로 질의가 몇 분씩 매달리지는 않았지만, 필터 검색 하나가
 * 개수 세기 + 목록으로 1 ~ 4초씩 커넥션을 쥐어 풀 15개(파드 3 × 5)가 늘 찼다 — 커넥션 대기 30 ~ 46, 받는 데 평균 2 ~ 3.5초, home · banners ·
 * 상품까지 5초 대기 끝에 5xx(실패 17.70% · 21.48%, 필터 검색 아닌 쪽 5xx 300여 건). 커넥션은 트랜잭션이 열릴 때 잡히므로 자리는 그 전에 얻는다.
 * 캐시 단일 계산({@code sync = true})이 같은 검색을 하나로 모은 뒤라 같은 키의 동시 요청은 자리를 하나만 쓴다.
 *
 * <p>🐛 2026-10-21 실습 8시간(mixed {@code 030018Z}, docs/83 §2.8): 끝난 뒤 쉬는 동안에도 파드 셋 모두 자리 3/3 을 스레드 셋이 쥐고
 * 있었다 — 모두 개수 세기({@code fetchOne})의 PostgreSQL 소켓 읽기(SSL)에서, 스레드는 몇 시간째. 시간 상한은 DB 에 취소 요청을 <b>새 연결로</b>
 * 보낼 뿐이라, 원래 연결이 죽었으면(노드 재부팅 · 사라진 상대 — RST 가 오지 않는다) 답이 돌아오지 않고 읽기는 깨어나지 않는다. 그 뒤로 DB 를 쓰는
 * 검색은 모두 0.2초 뒤 503 이었다(실패 4.64% — 5xx 28만 건 중 검색 셋이 27.9만), 풀(5)에도 둘만 남았다. 소켓 상한이면 그 연결은 버려지고
 * (SQLSTATE 08006 — Hikari 가 풀에서 뺀다) 자리는 돌아온다.
 *
 * <p>[JA] 検索の DB 作業の隔壁 — Pod 内で同時に DB を使う検索数に上限を置き、席を得てからトランザクション(コネクション)を開く。
 *
 * <ul>
 *   <li>検索全体 {@code catalog.search.bulkhead.max-concurrent}(既定 3)— プール(Pod ごとに 5)の残りは home · banners · 商品 · カート側の分。</li>
 *   <li>その中で価格 · 色 · サイズのフィルター検索 {@code catalog.search.bulkhead.max-concurrent-filtered}(既定 2)— フィルターなしの検索が一席は常に使える。</li>
 *   <li>席を {@code catalog.search.bulkhead.wait}(既定 200ms)までしか待たず、得られなければコネクションに触れずに {@link Busy}(503) —
 *       キャッシュがヒットする検索({@code search_cache})はここまで来ない。</li>
 *   <li>トランザクションは読み取り専用 · 時間上限 {@code catalog.search.query-timeout-seconds}(既定 5 秒 — 超えると DB で取り消し、SearchController が 503)。</li>
 *   <li>そのコネクションのソケット読み取りも上限 + {@code catalog.search.network-timeout-grace}(既定 5 秒)までしか待たない — コネクションがプールに
 *       戻ると Hikari が元の値に戻す。</li>
 * </ul>
 *
 * <p>🐛 2026-10-20 実習(商品 1,000 万 · フィルター検索比率 1、docs/83 §2.8): 時間上限のおかげでクエリが何分もぶら下がることはなかったが、フィルター検索
 * 一つが件数カウント + 一覧で 1 〜 4 秒ずつコネクションを握り、プール 15 個(Pod 3 × 5)が常に埋まった — コネクション待ち 30 〜 46、取得まで平均
 * 2 〜 3.5 秒、home · banners · 商品まで 5 秒待った末に 5xx(失敗 17.70% · 21.48%、フィルター検索以外の 5xx 300 件余り)。コネクションは
 * トランザクションが開くときに取られるので、席はその前に得る。キャッシュの単一計算({@code sync = true})が同じ検索を一つにまとめた後なので、
 * 同じキーの同時リクエストは席を一つしか使わない。
 *
 * <p>🐛 2026-10-21 実習 8 時間(mixed {@code 030018Z}、docs/83 §2.8): 終わった後の休止中も Pod 三つすべてで席 3/3 をスレッド三つが握っていた —
 * すべて件数カウント({@code fetchOne})の PostgreSQL ソケット読み取り(SSL)で、スレッドは数時間そのまま。時間上限は DB に取り消し要求を
 * <b>新しい接続で</b>送るだけなので、元の接続が死んでいると(ノード再起動 · 消えた相手 — RST が来ない)答えが返らず読み取りは起きない。
 * その後 DB を使う検索はすべて 0.2 秒後に 503 だった(失敗 4.64% — 5xx 28 万件のうち検索三つが 27.9 万)、プール(5)にも二つしか残らなかった。
 * ソケット上限ならその接続は捨てられ(SQLSTATE 08006 — Hikari がプールから外す)、席は戻る。
 */
@Slf4j
@Component
public class SearchBulkhead {

    /** 자리를 못 얻었다 — 커넥션은 잡지 않았다(SearchController 가 503 · Retry-After). */
    // [JA] 枠を得られなかった — コネクションは取っていない(SearchController が 503・Retry-After)。
    public static class Busy extends RuntimeException {
        private final String limit;

        Busy(String limit) {
            super("search bulkhead full: " + limit, null, false, false);
            this.limit = limit;
        }

        public String getLimit() {
            return limit;
        }
    }

    private final TransactionOperations transaction;
    private final Semaphore all;
    private final Semaphore filtered;
    private final long waitNanos;
    private final Counter rejectedAll;
    private final Counter rejectedFiltered;

    @Autowired
    public SearchBulkhead(PlatformTransactionManager transactionManager,
            @Value("${catalog.search.query-timeout-seconds:5}") int timeoutSeconds,
            @Value("${catalog.search.bulkhead.max-concurrent:3}") int maxConcurrent,
            @Value("${catalog.search.bulkhead.max-concurrent-filtered:2}") int maxFiltered,
            @Value("${catalog.search.bulkhead.wait:200ms}") Duration wait,
            @Value("${catalog.search.network-timeout-grace:5s}") Duration networkGrace,
            MeterRegistry registry) {
        this(readOnly(transactionManager, timeoutSeconds, networkGrace), maxConcurrent, maxFiltered, wait, registry);
    }

    SearchBulkhead(TransactionOperations transaction, int maxConcurrent, int maxFiltered, Duration wait, MeterRegistry registry) {
        this.transaction = transaction;
        this.all = new Semaphore(Math.max(1, maxConcurrent), true);
        this.filtered = new Semaphore(Math.max(1, Math.min(maxFiltered, maxConcurrent)), true);
        this.waitNanos = Math.max(0, wait.toNanos());
        this.rejectedAll = counter(registry, "all");
        this.rejectedFiltered = counter(registry, "filtered");
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager, int timeoutSeconds, Duration networkGrace) {
        if (!(transactionManager instanceof JpaTransactionManager jpa) || jpa.getEntityManagerFactory() == null) {
            throw new IllegalStateException("검색 격벽은 JPA 트랜잭션 관리자가 필요하다: " + transactionManager.getClass().getName());
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setTimeout(timeoutSeconds);
        int socketMillis = (int) Math.min(Integer.MAX_VALUE, Duration.ofSeconds(timeoutSeconds).plus(networkGrace).toMillis());
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                RuntimeException[] failure = new RuntimeException[1];
                try {
                    return template.execute(status -> {
                        boundSocketReads(jpa, socketMillis);
                        try {
                            return action.doInTransaction(status);
                        } catch (RuntimeException e) {
                            failure[0] = e;
                            throw e;
                        }
                    });
                } catch (RuntimeException e) {
                    // 버려진 연결에서는 롤백도 실패한다('Unable to rollback') — 그 예외가 원래 원인(소켓 시간 초과)을 덮지 않게 한다
                    // [JA] 捨てられた接続ではロールバックも失敗する('Unable to rollback') — その例外が元の原因(ソケットタイムアウト)を覆わないようにする
                    if (failure[0] != null && e != failure[0]) {
                        failure[0].addSuppressed(e);
                        throw failure[0];
                    }
                    throw e;
                }
            }
        };
    }

    /**
     * 이 트랜잭션의 커넥션에 소켓 읽기 상한을 건다({@link java.sql.Connection#setNetworkTimeout}) — 넘으면 PgJDBC 가 연결을 버리고 08006.
     * Hikari 는 바꾼 값을 기억했다가 커넥션이 풀로 돌아올 때 되돌린다(다른 요청 · 필터 메뉴 계산 600초에는 번지지 않는다).
     *
     * <p>[JA] このトランザクションのコネクションにソケット読み取りの上限をかける({@link java.sql.Connection#setNetworkTimeout}) — 超えると PgJDBC が接続を捨てて 08006。
     * Hikari は変えた値を覚えておき、コネクションがプールに戻るときに元に戻す(他のリクエスト・フィルタメニュー計算の 600 秒には波及しない)。
     */
    private static void boundSocketReads(JpaTransactionManager jpa, int millis) {
        EntityManagerFactoryUtils.getTransactionalEntityManager(jpa.getEntityManagerFactory()).unwrap(Session.class)
                .doWork(connection -> connection.setNetworkTimeout(Runnable::run, millis));
    }

    private static Counter counter(MeterRegistry registry, String limit) {
        return Counter.builder("catalog.search.bulkhead.rejected")
                .description("검색 격벽이 자리를 못 줘 503 으로 돌려보낸 수 — all(검색 전체) · filtered(필터 검색)")
                .tag("limit", limit)
                .register(registry);
    }

    /**
     * 자리를 얻고(필터 검색이면 두 자리) 읽기 전용 · 시간 상한 트랜잭션 안에서 {@code search} 를 돌린다.
     *
     * @throws Busy 기다림 상한 안에 자리를 못 얻었다 — 커넥션을 잡기 전이다
     *
     * <p>[JA] 枠を得て(フィルタ検索なら二枠)、読み取り専用・時間上限付きトランザクションの中で {@code search} を回す。
     * Busy: 待ち時間の上限内に枠を得られなかった — コネクションを取る前である。
     */
    public <T> T run(boolean filteredSearch, Supplier<T> search) {
        long deadline = System.nanoTime() + waitNanos;
        if (filteredSearch && !acquire(filtered, deadline)) {
            throw busy(rejectedFiltered, "filtered");
        }
        try {
            if (!acquire(all, deadline)) {
                throw busy(rejectedAll, "all");
            }
            try {
                return transaction.execute(status -> search.get());
            } finally {
                all.release();
            }
        } finally {
            if (filteredSearch) {
                filtered.release();
            }
        }
    }

    private static boolean acquire(Semaphore permits, long deadlineNanos) {
        try {
            return permits.tryAcquire(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Busy busy(Counter counter, String limit) {
        counter.increment();
        // 몰릴 때는 초당 수백 건이다 — 수는 지표(catalog_search_bulkhead_rejected_total)로 본다
        // [JA] 集中するときは毎秒数百件になる — 件数はメトリクス(catalog_search_bulkhead_rejected_total)で見る
        log.debug("검색 격벽이 찼다 — 503 limit={}", limit);
        return new Busy(limit);
    }
}
