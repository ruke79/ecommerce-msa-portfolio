package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import com.project.userservice.client.NotificationServiceClient;

/**
 * 데모 계정 매일 초기화(docs/100) — 꺼짐 · 다른 파드가 임대를 쥠이면 아무것도 하지 않고, 켜져 있으면 찜 · 배송지 · 기기 기록을 지운 뒤
 * notification 을 부른다. 알림 호출이 실패해도 예외를 올리지 않고 임대는 푼다.
 * [JA] デモアカウントの毎日の初期化 — 無効 · 他 Pod がリースを持つなら何もしない。有効ならお気に入り · 配送先 · 端末記録を消して notification を呼ぶ。
 */
@DisplayName("데모 계정 매일 초기화 — 한 파드 · user 표 셋 · 알림 호출")
class DemoAccountResetTest {

    DemoAccountService demo = mock(DemoAccountService.class);
    SchedulerLease lease = mock(SchedulerLease.class);
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    NotificationServiceClient notifications = mock(NotificationServiceClient.class);

    DemoAccountReset reset() {
        return new DemoAccountReset(demo, lease, jdbc, TransactionOperations.withoutTransaction(), notifications);
    }

    @Test
    @DisplayName("꺼져 있으면 임대도 잡지 않는다")
    void disabled() {
        assertThat(reset().run()).isEqualTo(-1);
        verifyNoInteractions(lease, jdbc, notifications);
    }

    @Test
    @DisplayName("다른 파드가 돌리는 중이면 건너뛴다")
    void leaseHeldElsewhere() {
        when(demo.enabled()).thenReturn(true);
        assertThat(reset().run()).isEqualTo(-1);
        verifyNoInteractions(jdbc, notifications);
    }

    @Test
    @DisplayName("🔴 켜져 있으면 데모 계정의 찜 · 배송지 · 기기 기록을 지우고 notification 에 같은 목록을 보낸다 · 임대를 푼다")
    void resetsTablesAndNotifies() {
        when(demo.enabled()).thenReturn(true);
        when(lease.tryAcquire(eq(DemoAccountReset.LEASE), any())).thenReturn(true);
        when(demo.allDemoAccountIds()).thenReturn(List.of(5L, 6L));
        when(jdbc.update(anyString(), anyMap())).thenReturn(2);

        assertThat(reset().run()).isEqualTo(6);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).update(sql.capture(), eq(Map.of("ids", List.of(5L, 6L))));
        assertThat(sql.getAllValues()).containsExactly(
                "DELETE FROM users.wishlist WHERE user_id IN (:ids)",
                "DELETE FROM users.shipping_address WHERE user_id IN (:ids)",
                "DELETE FROM users.known_device WHERE user_id IN (:ids)");
        verify(notifications).demoReset(new NotificationServiceClient.DemoResetRequest(List.of(5L, 6L)));
        verify(lease).release(DemoAccountReset.LEASE);
    }

    @Test
    @DisplayName("알림 호출이 실패해도 예외 없이 끝나고 임대를 푼다 — 다음 날 다시")
    void notificationFailureIsSwallowed() {
        when(demo.enabled()).thenReturn(true);
        when(lease.tryAcquire(eq(DemoAccountReset.LEASE), any())).thenReturn(true);
        when(demo.allDemoAccountIds()).thenReturn(List.of(5L));
        when(notifications.demoReset(any())).thenThrow(new IllegalStateException("down"));

        reset().run();
        verify(lease).release(DemoAccountReset.LEASE);
    }

    @Test
    @DisplayName("데모 계정이 없으면 지우지 않는다")
    void noAccounts() {
        when(demo.enabled()).thenReturn(true);
        when(lease.tryAcquire(eq(DemoAccountReset.LEASE), any())).thenReturn(true);
        when(demo.allDemoAccountIds()).thenReturn(List.of());
        assertThat(reset().run()).isZero();
        verify(jdbc, never()).update(anyString(), anyMap());
        verifyNoInteractions(notifications);
    }
}
