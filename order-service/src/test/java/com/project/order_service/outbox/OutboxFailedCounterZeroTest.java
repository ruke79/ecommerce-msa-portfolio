package com.project.order_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.outbox.scheduler.PaymentOutboxScheduler;
import com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 아웃박스 FAILED 계수기는 이벤트 종류마다 기동 때 0 으로 있다(B-101).
 *
 * <p>🐛 2026-10-22 실습 게임데이(kafka-pause): order-status 행 3개가 FAILED 였는데 경보 OrderOutboxRowFailed 가 울리지 않았다 —
 * 계수기가 첫 실패 때 생겨 첫 샘플이 이미 1(또는 3)이라 {@code increase(order_outbox_failed_total[30m])} 가 0 이었다.
 * [JA] FAILED カウンターは種類ごとに起動時 0 で存在する。初回の失敗で生まれると increase() が 0 になり警報が鳴らなかった。
 */
@DisplayName("order-service — 아웃박스 FAILED 계수기를 0 으로 미리")
class OutboxFailedCounterZeroTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void attach() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    void detach() {
        Metrics.removeRegistry(meters);
    }

    @Test
    @DisplayName("🔴 기동 때 order.outbox.failed{type} 가 네 종류(payment-request · order-status · product-update · cart-empty) 모두 0")
    void everyEventTypeStartsAtZero() {
        PaymentOutboxScheduler relay = new PaymentOutboxScheduler(mock(PaymentOutboxHelper.class),
                mock(PaymentRequestKafkaPublisher.class), mock(OrderStatusKafkaPublisher.class), OutboxTraceContext.NOOP,
                mock(ProductUpdateKafkaPublisher.class), mock(CartEmptyKafkaPublisher.class));

        relay.registerAlertCounters();

        for (String type : List.of("payment-request", PaymentOutboxHelper.ORDER_STATUS_EVENT,
                PaymentOutboxHelper.PRODUCT_UPDATE_EVENT, PaymentOutboxHelper.CART_EMPTY_EVENT)) {
            Counter c = meters.find(PaymentOutboxScheduler.FAILED_METRIC).tag("type", type).counter();
            assertThat(c).as("종류 %s", type).isNotNull();
            assertThat(c.count()).isZero();
        }
    }
}
