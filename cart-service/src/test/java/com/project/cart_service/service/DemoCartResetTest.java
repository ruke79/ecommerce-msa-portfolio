package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.cart_service.client.UserServiceClient;

/**
 * 데모 장바구니 매일 초기화(docs/100) — 꺼져 있으면 묻지도 않고, 켜져 있으면 받은 계정마다 비운다. 한 계정 실패 · 목록 실패가 나머지를
 * 막지 않는다.
 * [JA] デモカートの毎日の初期化 — 無効なら聞きもせず、有効なら受け取ったアカウントごとに空にする。一件の失敗が残りを止めない。
 */
@DisplayName("데모 장바구니 매일 초기화")
class DemoCartResetTest {

    UserServiceClient users = mock(UserServiceClient.class);
    CartService carts = mock(CartService.class);

    @Test
    @DisplayName("꺼져 있으면 user-service 에 묻지도 않는다")
    void disabledDoesNothing() {
        assertThat(new DemoCartReset(false, users, carts).run()).isZero();
        verifyNoInteractions(users, carts);
    }

    @Test
    @DisplayName("🔴 켜져 있으면 데모 계정마다 비운다 — 한 계정 실패는 건너뛰고 나머지는 계속")
    void resetsEachDemoAccount() {
        when(users.demoAccounts()).thenReturn(Map.of("userIds", List.of(1L, 2L, 3L)));
        doThrow(new IllegalStateException("db")).when(carts).resetDemoCart(2L);

        assertThat(new DemoCartReset(true, users, carts).run()).isEqualTo(2);
        verify(carts).resetDemoCart(1L);
        verify(carts).resetDemoCart(3L);
    }

    @Test
    @DisplayName("목록을 받지 못하면 아무것도 지우지 않고 -1(내일 다시)")
    void listFailureSkips() {
        when(users.demoAccounts()).thenThrow(new IllegalStateException("down"));
        assertThat(new DemoCartReset(true, users, carts).run()).isEqualTo(-1);
        verify(carts, never()).resetDemoCart(anyLong());
    }
}
