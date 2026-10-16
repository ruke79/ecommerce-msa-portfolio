package com.project.userservice.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 등급 배치 — 임대를 잡은 파드만 돈다.
 *
 * <p>🐛 예전: 레플리카마다 같은 시각에 돌아, 늦게 시작한 회차가 먼저 끝나면 앞 회차가 매긴 VIP 를 BASIC 으로 내렸다.
 *
 * <p>[JA] 等級バッチ — リースを取った Pod だけが走る。
 */
@DisplayName("등급 배치 스케줄러 — 한 회차는 한 파드만")
class CustomerTierSchedulerTest {

    private CustomerTierService service;
    private SchedulerLease lease;
    private CustomerTierScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(CustomerTierService.class);
        lease = mock(SchedulerLease.class);
        scheduler = new CustomerTierScheduler(service, lease);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        when(service.recalculateAll()).thenReturn(new CustomerTierService.Result(1, 0, true));
    }

    @Test
    @DisplayName("🔴 다른 파드가 임대를 쥐고 있으면 돌지 않는다")
    void skipsWhenAnotherPodHoldsTheLease() {
        when(lease.tryAcquire(eq(CustomerTierScheduler.LEASE), any())).thenReturn(false);

        scheduler.recalculate();

        verify(service, never()).recalculateAll();
        verify(lease, never()).release(any());
    }

    @Test
    @DisplayName("임대를 잡으면 돌고, 실패해도 푼다")
    void runsAndReleasesEvenOnFailure() {
        when(lease.tryAcquire(eq(CustomerTierScheduler.LEASE), any())).thenReturn(true);
        when(service.recalculateAll()).thenThrow(new IllegalStateException("boom"));

        scheduler.recalculate();

        verify(service).recalculateAll();
        verify(lease).release(CustomerTierScheduler.LEASE);
    }

    @Test
    @DisplayName("임대를 잡지 못하는 오류(DB)면 돌지 않는다 — 막을 수 없을 때 두 번 도는 쪽보다 한 번 건너뛰는 쪽")
    void skipsWhenTheLeaseCannotBeChecked() {
        when(lease.tryAcquire(any(), any())).thenThrow(new IllegalStateException("db down"));

        scheduler.recalculate();

        verify(service, never()).recalculateAll();
    }
}
