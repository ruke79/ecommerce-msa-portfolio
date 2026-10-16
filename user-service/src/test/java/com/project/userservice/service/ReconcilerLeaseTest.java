package com.project.userservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.message.UserOutboxHelper;
import com.project.userservice.repository.jpa.ReviewRepository;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 평점 대사 · 복제 대사 — 레플리카가 여럿이어도 회차마다 한 파드만 돈다(SchedulerLease, V17).
 *
 * <p>🐛 예전: 두 파드가 같은 시각에 돌아 catalog 호출 · 아웃박스 행이 파드 수만큼 늘었다(사용자 10만 명이면 하룻밤 약 40만 행).
 *
 * <p>[JA] 評価の照合・複製の照合 — レプリカが複数でも回ごとに一つの Pod だけが走る。以前は Pod の数だけ呼び出し・アウトボックス行が増えた。
 */
@DisplayName("대사 배치 — 회차마다 한 파드")
class ReconcilerLeaseTest {

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final CatalogServiceClient catalog = mock(CatalogServiceClient.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SchedulerLease lease = mock(SchedulerLease.class);

    private ReviewRatingReconciler rating() {
        when(reviews.averageRatingByProduct()).thenReturn(List.<Object[]>of(new Object[] { 1L, 4.5 }));
        return new ReviewRatingReconciler(reviews, catalog, lease);
    }

    private UserReplicationReconciler replication() {
        return new UserReplicationReconciler(users, mock(UserOutboxHelper.class), mock(PlatformTransactionManager.class), lease);
    }

    @Test
    @DisplayName("🔴 다른 파드가 임대를 쥐었으면 평점 대사 · 복제 대사 모두 아무것도 하지 않는다")
    void skipWhenAnotherPodHoldsTheLease() {
        when(lease.tryAcquire(any(), any())).thenReturn(false);

        rating().scheduled();
        replication().scheduled();

        verify(reviews, never()).averageRatingByProduct();
        verify(catalog, never()).updateRating(any(), anyFloat());
        verify(users, never()).findTouchedSince(any(), anyLong(), any());
    }

    @Test
    @DisplayName("🔴 임대를 잡으면 돌고, 끝나도 풀지 않는다 — 시계가 늦은 파드가 같은 회차를 다시 돌지 않게")
    void runOnceAndKeepTheLease() {
        when(lease.tryAcquire(eq(ReviewRatingReconciler.LEASE), any())).thenReturn(true);
        when(lease.tryAcquire(eq(UserReplicationReconciler.LEASE), any())).thenReturn(true);
        when(users.findTouchedSince(any(), anyLong(), any())).thenReturn(List.of());

        rating().scheduled();
        replication().scheduled();

        verify(catalog).updateRating(1L, 4.5F);
        verify(users).findTouchedSince(any(), eq(0L), any());
        verify(lease, never()).release(any());
    }

    @Test
    @DisplayName("임대 확인이 실패(DB)하면 건너뛴다 — 다음 회차가 같은 일을 한다")
    void skipWhenTheLeaseCannotBeChecked() {
        when(lease.tryAcquire(any(), any())).thenThrow(new IllegalStateException("db down"));

        rating().scheduled();
        replication().scheduled();

        verify(reviews, never()).averageRatingByProduct();
        verify(users, never()).findTouchedSince(any(), anyLong(), any());
    }
}
