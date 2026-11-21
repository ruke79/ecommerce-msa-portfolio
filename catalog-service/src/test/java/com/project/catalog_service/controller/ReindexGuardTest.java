package com.project.catalog_service.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.project.catalog_service.service.ReindexLock;
import com.project.catalog_service.service.SearchEngineSelector;
import com.project.catalog_service.service.SearchFilterFacets;

/**
 * 전체 재색인({@code POST /api/products/sync})의 중복 실행 방지 — 파드 수와 무관해야 한다.
 *
 * <p>🐛 2026-11-20 실습: 방지가 컨트롤러의 {@code AtomicBoolean} 이라 catalog 레플리카 2개에서 게이트웨이가 다른 파드로
 * 보낸 두 번째 요청이 그냥 통과했다 — 재색인 잡 둘이 21시간 함께 돌며 싱글턴 리더를 나눠 읽었다. 이제 Redis 잠금이다.
 *
 * <p>[JA] 再索引の重複実行防止はポッド数に依らないこと。
 */
@DisplayName("재색인 — 중복 실행 방지(클러스터 전체)")
class ReindexGuardTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);

    private SearchController controller(ReindexLock lock) {
        return new SearchController(null, null, null, jobLauncher,
                Map.of("indexingJob", mock(Job.class)), mock(SearchFilterFacets.class),
                mock(SearchEngineSelector.class), lock);
    }

    @Test
    @DisplayName("🔴 잠금을 잡지 못하면 409 이고 잡을 띄우지 않는다 — 다른 파드가 이미 돌고 있다는 뜻이다")
    void conflictWhenHeldElsewhere() throws Exception {
        ReindexLock held = new ReindexLock() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public void release() {
                throw new AssertionError("잡지 못했으면 풀지 않는다 — 남의 잠금을 지운다");
            }
        };

        ResponseEntity<String> res = controller(held).syncProducts();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).contains("이미 진행 중");
        verify(jobLauncher, never()).run(any(), any());
    }

    @Test
    @DisplayName("🔴 Redis 에 닿지 못하면 503 — 막지 못한 채 두 번째 잡을 띄우지 않는다(fail-closed)")
    void refusesWhenLockCannotBeChecked() throws Exception {
        ReindexLock broken = new ReindexLock() {
            @Override
            public boolean tryAcquire() {
                throw new IllegalStateException("redis 에 닿지 못했다");
            }

            @Override
            public void release() {
            }
        };

        ResponseEntity<String> res = controller(broken).syncProducts();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).contains("Redis");
        verify(jobLauncher, never()).run(any(), any());
    }

    @Test
    @DisplayName("🔴 잠금을 잡으면 202 로 바로 돌아오고, 끝나면 잠금을 푼다")
    void acceptsAndReleasesWhenDone() throws Exception {
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        ReindexLock lock = new ReindexLock() {
            @Override
            public boolean tryAcquire() {
                return true;
            }

            @Override
            public void release() {
                released.countDown();
            }
        };

        ResponseEntity<String> res = controller(lock).syncProducts();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody()).startsWith("Batch synchronization job started: products-");
        assertThat(released.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("잡이 끝나면(성공이든 실패든) 잠금을 푼다 — 안 풀면 리스(12시간)까지 막힌다").isTrue();
    }
}
