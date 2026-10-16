package com.project.order_service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.project.common.constants.OrderStatus;
import com.project.order_service.repository.jpa.OrderRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 결제 대사 — 결제는 기록됐는데 결제 응답이 오지 않은 주문을 센다. 예전에는 아무도 몰랐다.
 *
 * <p>[JA] 決済照合 — 決済は記録されたのに決済応答が来ない注文を数える。
 */
@DisplayName("order-service — 결제 대사(결제 기록됨 · 결제 전)")
class UnconfirmedPaymentWatchTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UnconfirmedPaymentWatch watch = new UnconfirmedPaymentWatch(orders, meters);

    @Test
    @DisplayName("🔴 결제 전(NOT_PROCESSED) · 10분 넘은 주문 수를 게이지로 — 기준 시각은 지금 − 10분")
    void countsUnconfirmedPayments() {
        watch.setClock(Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC));
        ArgumentCaptor<LocalDateTime> before = ArgumentCaptor.forClass(LocalDateTime.class);
        when(orders.countUnconfirmedPayments(eq(OrderStatus.NOT_PROCESSED), before.capture())).thenReturn(3L);
        when(orders.findUnconfirmedPayments(eq(OrderStatus.NOT_PROCESSED), any(), any())).thenReturn(List.of(1L, 2L, 3L));

        watch.check();

        assertThat(before.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 15, 11, 50));
        assertThat(meters.get(UnconfirmedPaymentWatch.METRIC).gauge().value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("대사가 실패해도 던지지 않는다 — 게이지는 마지막 값")
    void failureKeepsLastValue() {
        when(orders.countUnconfirmedPayments(any(), any())).thenReturn(2L);
        watch.check();
        when(orders.countUnconfirmedPayments(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        watch.check();

        assertThat(watch.unconfirmed()).isEqualTo(2);
    }
}
