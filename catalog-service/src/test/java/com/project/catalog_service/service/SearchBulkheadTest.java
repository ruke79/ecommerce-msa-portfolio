package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 검색 격벽 — 전체 3 · 필터 검색 2, 자리를 못 얻으면 트랜잭션을 열지 않고 {@link SearchBulkhead.Busy}.
 *
 * <p>🐛 2026-10-20 실습(상품 1,000만 · 필터 검색 비율 1): 필터 검색이 커넥션을 1 ~ 4초씩 쥐어 풀 15개가 늘 찼고 home · 상품까지 5xx 였다.
 * 실제 풀 · 잠금으로 보는 쪽은 SearchFacetsTimeoutIntegrationTest.
 *
 * <p>[JA] 検索の隔壁 — 全体 3・フィルター検索 2、席を得られなければトランザクションを開かずに Busy。
 */
@DisplayName("검색 격벽 — 전체 3 · 필터 검색 2 · 넘치면 트랜잭션 전에 거절")
class SearchBulkheadTest {

    /** 상한 없음 · 트랜잭션 없음 — 격벽과 상관없는 SearchService 단위 테스트용. */
    static SearchBulkhead direct() {
        return new SearchBulkhead(TransactionOperations.withoutTransaction(), 1_000, 1_000, Duration.ZERO, new SimpleMeterRegistry());
    }

    /** 트랜잭션을 연 횟수를 센다 — 거절된 검색은 열지 않아야 한다(= 커넥션을 잡지 않는다). */
    static class CountingTransactions implements TransactionOperations {
        final AtomicInteger opened = new AtomicInteger();

        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            opened.incrementAndGet();
            return action.doInTransaction(null);
        }
    }

    @Test
    @DisplayName("🔴 필터 검색은 두 개까지 — 셋째는 기다림 상한(0.1초) 뒤 Busy · 트랜잭션을 열지 않는다, 그동안 필터 없는 검색은 돈다")
    void filteredSearchesAreCappedAtTwo() throws Exception {
        CountingTransactions tx = new CountingTransactions();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SearchBulkhead bulkhead = new SearchBulkhead(tx, 3, 2, Duration.ofMillis(100), registry);
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                running.add(pool.submit(() -> bulkhead.run(true, () -> {
                    inside.countDown();
                    await(release);
                    return "slow";
                })));
            }
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> bulkhead.run(true, () -> "third"))
                    .isInstanceOfSatisfying(SearchBulkhead.Busy.class, b -> assertThat(b.getLimit()).isEqualTo("filtered"));
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).as("기다림 상한까지만").isLessThan(2_000);
            assertThat(tx.opened.get()).as("거절된 검색은 트랜잭션(커넥션)을 열지 않았다").isEqualTo(2);

            assertThat(bulkhead.run(false, () -> "plain")).as("전체 3 중 남은 한 자리").isEqualTo("plain");
            assertThat(registry.get("catalog.search.bulkhead.rejected").tag("limit", "filtered").counter().count()).isEqualTo(1.0);

            release.countDown();
            for (Future<String> f : running) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("slow");
            }
            assertThat(bulkhead.run(true, () -> "again")).as("끝나면 자리가 돌아온다").isEqualTo("again");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("🔴 검색 전체는 세 개까지 — 필터 없는 검색 셋이 쥐고 있으면 넷째는 Busy(all)")
    void allSearchesAreCappedAtThree() throws Exception {
        CountingTransactions tx = new CountingTransactions();
        SearchBulkhead bulkhead = new SearchBulkhead(tx, 3, 2, Duration.ofMillis(50), new SimpleMeterRegistry());
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int i = 0; i < 3; i++) {
                pool.submit(() -> bulkhead.run(false, () -> {
                    inside.countDown();
                    await(release);
                    return null;
                }));
            }
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> bulkhead.run(false, () -> "fourth"))
                    .isInstanceOfSatisfying(SearchBulkhead.Busy.class, b -> assertThat(b.getLimit()).isEqualTo("all"));
            assertThatThrownBy(() -> bulkhead.run(true, () -> "filtered"))
                    .as("필터 검색 자리를 얻어도 전체가 차면 거절 — 필터 자리는 돌려준다").isInstanceOf(SearchBulkhead.Busy.class);
            assertThat(tx.opened.get()).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        // 위의 거절에서 필터 자리가 새지 않았다 — 두 개가 그대로 있다
        CountDownLatch hold = new CountDownLatch(1);
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch in = new CountDownLatch(2);
            for (int i = 0; i < 2; i++) {
                two.submit(() -> bulkhead.run(true, () -> {
                    in.countDown();
                    await(hold);
                    return null;
                }));
            }
            assertThat(in.await(5, TimeUnit.SECONDS)).as("필터 자리 둘이 모두 남아 있다").isTrue();
        } finally {
            hold.countDown();
            two.shutdownNow();
        }
    }

    @Test
    @DisplayName("검색이 실패해도 자리를 돌려준다")
    void permitsAreReleasedOnFailure() {
        SearchBulkhead bulkhead = new SearchBulkhead(new CountingTransactions(), 1, 1, Duration.ZERO, new SimpleMeterRegistry());
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> bulkhead.run(true, () -> {
                throw new IllegalStateException("query failed");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(bulkhead.run(true, () -> "ok")).isEqualTo("ok");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
