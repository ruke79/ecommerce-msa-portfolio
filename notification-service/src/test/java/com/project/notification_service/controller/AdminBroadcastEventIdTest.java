package com.project.notification_service.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자 안내의 멱등 키 — 연타는 같은 키, 다른 안내는 다른 키.
 *
 * <p>🐛 예전: {@code "admin:" + LocalDateTime.now()} — 나노초까지라 저장 버튼을 두 번 누르면 매번 다른 키가 되어 같은 안내가 두 줄 생겼다.
 *
 * <p>[JA] 管理者案内の冪等キー — 連打は同じキー、別の案内は別キー。
 */
@DisplayName("관리자 안내 — 멱등 키")
class AdminBroadcastEventIdTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 16, 9, 30, 12, 345_678_901);

    @Test
    @DisplayName("🔴 같은 내용을 같은 분 안에 다시 보내면 같은 키 — 연타가 두 줄을 만들지 않는다")
    void doubleClickSharesTheKey() {
        assertThat(BroadcastAdminController.adminEventId("점검 안내", "02시 점검", null, T))
                .isEqualTo(BroadcastAdminController.adminEventId("점검 안내", "02시 점검", null, T.plusNanos(987_654).plusSeconds(20)));
    }

    @Test
    @DisplayName("같은 분이라도 내용이 다르면 다른 키 · 다음 분에는 같은 내용도 다시 보낼 수 있다")
    void differentContentOrLaterMinuteIsNew() {
        String key = BroadcastAdminController.adminEventId("점검 안내", "02시 점검", null, T);
        assertThat(BroadcastAdminController.adminEventId("점검 안내", "03시 점검", null, T)).isNotEqualTo(key);
        assertThat(BroadcastAdminController.adminEventId("점검 안내", "02시 점검", null, T.plusMinutes(1))).isNotEqualTo(key);
        assertThat(key).hasSizeLessThan(255).startsWith("admin:");
    }
}
