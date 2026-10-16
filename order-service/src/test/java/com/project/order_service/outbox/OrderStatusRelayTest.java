package com.project.order_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.scheduler.PaymentOutboxScheduler;
import com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;

/**
 * 릴레이가 {@code order-status} 행을 안다(docs/76). 모르면 "Unknown outbox event type" 으로 FAILED 를 찍어
 * 상태 변경 이벤트가 조용히 사라진다 — 롤링 중 옛 파드가 그렇게 한다(docs/76 §5 의 복구 쿼리).
 * [JA] リレーが order-status 行を知っている(docs/76)。知らなければ FAILED にして状態変更イベントが静かに消える。
 */
@DisplayName("order-service — 아웃박스 릴레이의 order-status 분기")
class OrderStatusRelayTest {

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 order-status 행은 OrderStatusKafkaPublisher 로 간다 — 행의 추적 문맥 아래에서")
    void orderStatusRowsGoToTheirPublisher() {
        PaymentOutboxEvent row = new PaymentOutboxEvent("42", "Order", PaymentOutboxHelper.ORDER_STATUS_EVENT,
                "{}", OutboxStatus.STARTED);
        row.setTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        PaymentOutboxHelper claiming = mock(PaymentOutboxHelper.class);
        when(claiming.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(row));
        OrderStatusKafkaPublisher publisher = mock(OrderStatusKafkaPublisher.class);
        when(publisher.publish(eq(row), any())).thenReturn(true);
        OutboxTraceContext traceContext = mock(OutboxTraceContext.class);
        when(traceContext.resume(anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());

        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(claiming,
                mock(PaymentRequestKafkaPublisher.class), publisher, traceContext,
                mock(com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher.class), mock(com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher.class));
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        scheduler.processOutboxMessage();

        verify(traceContext).resume(eq(row.getTraceContext()), eq("outbox relay order-status"), any(Supplier.class));
        verify(publisher).publish(eq(row), any());
        assertThat(row.getOutboxStatus()).as("상태 확정은 발행기의 콜백이 한다 — 여기서는 그대로").isEqualTo(OutboxStatus.STARTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 product-update · cart-empty 행은 각 발행기로 간다 — 모르는 타입으로 FAILED 가 되지 않는다")
    void sagaFollowUpRowsGoToTheirPublishers() {
        PaymentOutboxEvent stock = new PaymentOutboxEvent("42", "Order", PaymentOutboxHelper.PRODUCT_UPDATE_EVENT, "{}", OutboxStatus.STARTED);
        PaymentOutboxEvent cart = new PaymentOutboxEvent("42", "Order", PaymentOutboxHelper.CART_EMPTY_EVENT, "{}", OutboxStatus.STARTED);
        PaymentOutboxHelper claiming = mock(PaymentOutboxHelper.class);
        when(claiming.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(stock, cart));
        var products = mock(com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher.class);
        var carts = mock(com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher.class);
        when(products.publish(eq(stock), any())).thenReturn(true);
        when(carts.publish(eq(cart), any())).thenReturn(true);
        OutboxTraceContext traceContext = mock(OutboxTraceContext.class);
        when(traceContext.resume(any(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());

        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(claiming, mock(PaymentRequestKafkaPublisher.class),
                mock(OrderStatusKafkaPublisher.class), traceContext, products, carts);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        scheduler.processOutboxMessage();

        verify(products).publish(eq(stock), any());
        verify(carts).publish(eq(cart), any());
        assertThat(stock.getOutboxStatus()).isNotEqualTo(OutboxStatus.FAILED);
        assertThat(cart.getOutboxStatus()).isNotEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("🔴 영구 오류 행 하나는 그 행만 FAILED — 뒤의 행은 그대로 보낸다(예전에는 배치째 롤백되어 전부 멈췄다)")
    void poisonRowDoesNotBlockTheRest() {
        PaymentOutboxEvent broken = new PaymentOutboxEvent("1", "Order", PaymentOutboxHelper.PRODUCT_UPDATE_EVENT, "{not json", OutboxStatus.STARTED);
        PaymentOutboxEvent next = new PaymentOutboxEvent("2", "Order", PaymentOutboxHelper.ORDER_STATUS_EVENT, "{}", OutboxStatus.STARTED);
        PaymentOutboxHelper claiming = mock(PaymentOutboxHelper.class);
        when(claiming.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(broken, next));
        var products = mock(com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher.class);
        when(products.publish(eq(broken), any())).thenThrow(new IllegalStateException("payload"));
        OrderStatusKafkaPublisher statuses = mock(OrderStatusKafkaPublisher.class);
        when(statuses.publish(eq(next), any())).thenReturn(true);
        OutboxTraceContext traceContext = mock(OutboxTraceContext.class);
        when(traceContext.resume(any(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(meters);
        try {
            PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(claiming, mock(PaymentRequestKafkaPublisher.class),
                    statuses, traceContext, products,
                    mock(com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher.class));
            ReflectionTestUtils.setField(scheduler, "batchSize", 10);
            scheduler.processOutboxMessage();

            assertThat(broken.getOutboxStatus()).isEqualTo(OutboxStatus.FAILED);
            verify(statuses).publish(eq(next), any());
            assertThat(meters.counter(PaymentOutboxScheduler.FAILED_METRIC, "type", PaymentOutboxHelper.PRODUCT_UPDATE_EVENT).count()).isEqualTo(1);
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(meters);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("DB 가 흔들리는 일시 실패는 FAILED 로 두지 않는다 — 배치째 다음 폴링으로")
    void transientFailureIsNotMarkedFailed() {
        PaymentOutboxEvent row = new PaymentOutboxEvent("1", "Order", PaymentOutboxHelper.ORDER_STATUS_EVENT, "{}", OutboxStatus.STARTED);
        PaymentOutboxHelper claiming = mock(PaymentOutboxHelper.class);
        when(claiming.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(row));
        OrderStatusKafkaPublisher statuses = mock(OrderStatusKafkaPublisher.class);
        when(statuses.publish(eq(row), any())).thenThrow(new org.springframework.dao.CannotAcquireLockException("lock"));
        OutboxTraceContext traceContext = mock(OutboxTraceContext.class);
        when(traceContext.resume(any(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());
        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(claiming, mock(PaymentRequestKafkaPublisher.class),
                statuses, traceContext, mock(com.project.order_service.ports.output.message.publisher.ProductUpdateKafkaPublisher.class),
                mock(com.project.order_service.ports.output.message.publisher.CartEmptyKafkaPublisher.class));
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::processOutboxMessage)
                .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        assertThat(row.getOutboxStatus()).isEqualTo(OutboxStatus.STARTED);
    }
}
