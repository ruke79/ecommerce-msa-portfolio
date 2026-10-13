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
import com.project.order_service.ports.output.message.publisher.TokenRequestKafkaPublisher;

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
                mock(PaymentRequestKafkaPublisher.class), mock(TokenRequestKafkaPublisher.class), publisher, traceContext);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        scheduler.processOutboxMessage();

        verify(traceContext).resume(eq(row.getTraceContext()), eq("outbox relay order-status"), any(Supplier.class));
        verify(publisher).publish(eq(row), any());
        assertThat(row.getOutboxStatus()).as("상태 확정은 발행기의 콜백이 한다 — 여기서는 그대로").isEqualTo(OutboxStatus.STARTED);
    }
}
