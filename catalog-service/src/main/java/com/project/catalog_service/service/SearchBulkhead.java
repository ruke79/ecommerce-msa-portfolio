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
 * <p>[JA] 検索の DB 作業の隔壁 — ポッド内で同時に DB を使う検索数に上限を置き、席を得てからトランザクション(接続)を開く。席を待てなければ
 * 接続に触れずに 503。1,000 万商品のフィルター検索が接続を 1〜4 秒ずつ握ってプール全体が埋まり、home・商品まで 5xx だった。
 *      その接続のソケット読み取りにも上限を置く — 時間上限の取り消しは別接続で送るだけなので、元の接続が死んでいると読み取りが永遠に
 *      起きず、席が戻らなかった(8 時間実習で全ポッドの席 3/3 が数時間ふさがった)。
 */
@Slf4j
@Component
public class SearchBulkhead {

    /** 자리를 못 얻었다 — 커넥션은 잡지 않았다(SearchController 가 503 · Retry-After). */
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
        log.debug("검색 격벽이 찼다 — 503 limit={}", limit);
        return new Busy(limit);
    }
}
