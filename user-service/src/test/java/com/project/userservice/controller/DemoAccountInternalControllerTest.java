package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.service.DemoAccountService;

/**
 * 데모 계정 목록(내부 전용, docs/100) — 게이트웨이를 거친 요청은 관리자라도 403.
 * [JA] デモアカウント一覧(内部専用)— ゲートウェイ経由は管理者でも 403。
 */
@DisplayName("데모 계정 목록 — 내부 호출만")
class DemoAccountInternalControllerTest {

    @Test
    @DisplayName("🔴 게이트웨이를 거친 요청(신원 헤더 있음)은 관리자라도 403 · 목록을 읽지 않는다")
    void gatewayCallsAreRejected() {
        DemoAccountService demo = mock(DemoAccountService.class);
        var res = new DemoAccountInternalController(demo).demoAccounts("ROLE_ADMIN", "a@example.com", "1");
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(demo, never()).allDemoAccountIds();
    }

    @Test
    @DisplayName("내부 호출(신원 헤더 없음)은 목록")
    void internalCallGetsList() {
        DemoAccountService demo = mock(DemoAccountService.class);
        when(demo.allDemoAccountIds()).thenReturn(List.of(5L, 6L));
        var res = new DemoAccountInternalController(demo).demoAccounts(null, null, null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("userIds", List.of(5L, 6L));
    }
}
