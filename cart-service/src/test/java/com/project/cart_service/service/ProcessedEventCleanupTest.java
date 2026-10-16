package com.project.cart_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.project.cart_service.CartServiceApplication;
import com.project.common.idempotency.ProcessedEventPurger;

/**
 * cart.processed_event 정리 — 예전에는 지우는 곳이 없어 처리한 이벤트마다 한 줄이 영구히 쌓였다. 규칙 자체는 common 의 ProcessedEventPurgerTest.
 *
 * <p>[JA] cart.processed_event の掃除。以前は削除する所がなく、処理したイベントごとに一行が永久に溜まっていた。
 */
@DisplayName("멱등 원장 정리 (cart.processed_event)")
class ProcessedEventCleanupTest {

    @Test
    @DisplayName("켜져 있으면 지우고, 끄면 지우지 않는다 — 실패는 다음 회차로 넘긴다")
    void runsThePurger() throws SQLException {
        ProcessedEventPurger purger = mock(ProcessedEventPurger.class);
        new ProcessedEventCleanup(true, purger).run();
        verify(purger).purge();

        ProcessedEventPurger off = mock(ProcessedEventPurger.class);
        new ProcessedEventCleanup(false, off).run();
        verify(off, never()).purge();

        ProcessedEventPurger failing = mock(ProcessedEventPurger.class);
        when(failing.purge()).thenThrow(new SQLException("db down"));
        new ProcessedEventCleanup(true, failing).run();
    }

    @Test
    @DisplayName("🔴 예약 실행이 켜져 있다 — @Scheduled 만 있고 @EnableScheduling 이 없으면 아무도 부르지 않는다")
    void isActuallyScheduled() throws NoSuchMethodException {
        assertThat(ProcessedEventCleanup.class.getMethod("run").getAnnotation(Scheduled.class)).isNotNull();
        assertThat(CartServiceApplication.class.getAnnotation(EnableScheduling.class)).isNotNull();
    }
}
