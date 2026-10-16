package com.project.order_service.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;
import com.project.order_service.outbox.scheduler.PaymentOutboxScheduler;
import com.project.order_service.ports.output.message.publisher.PaymentRequestKafkaPublisher;

/**
 * 결제 아웃박스를 건너는 추적 (docs/51 §4e).
 *
 * <p>행을 쓰는 순간(결제 확인 요청)의 추적 문맥을 행에 남기고, 스케줄러가 그 문맥 아래에서 보낸다.
 * 실제 추적기(OpenTelemetry)로 헤더가 이어지는 것은 common 의 {@code OutboxTraceContextTest} 가 보고, 여기서는
 * <b>이 서비스가 그 두 끝을 제자리에서 부르는가</b>를 본다.
 */
@DisplayName("order-service — 결제 아웃박스의 추적 문맥")
class PaymentOutboxTraceTest {

    private static final String REQUEST_CONTEXT = "traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\n";

    private PaymentOutboxRepository repository;
    private OutboxTraceContext traceContext;
    private PaymentOutboxHelper helper;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentOutboxRepository.class);
        when(repository.save(any(PaymentOutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        traceContext = mock(OutboxTraceContext.class);
        when(traceContext.capture()).thenReturn(REQUEST_CONTEXT);
        helper = new PaymentOutboxHelper(repository, new ObjectMapper(), traceContext, OutboxRelayTrigger.DISABLED);
    }

    private static PaymentOutboxEvent newRow() {
        return new PaymentOutboxEvent("1", "Order", "payment-request", "{}", OutboxStatus.STARTED);
    }

    @Test
    @DisplayName("🔴 새 행을 쓸 때 요청의 추적 문맥을 남긴다")
    void newRowKeepsTheRequestContext() {
        PaymentOutboxEvent row = newRow();

        helper.save(row);

        assertThat(row.getTraceContext()).isEqualTo(REQUEST_CONTEXT);
    }

    /** 같은 save() 가 릴레이의 상태 갱신에도 쓰인다. 그때 남기면 스케줄러의 문맥으로 덮어쓴다. */
    @Test
    @DisplayName("🔴 릴레이가 상태를 바꿀 때는 덮어쓰지 않는다 — 불러온 행은 새 행이 아니다")
    void statusUpdateDoesNotOverwrite() {
        PaymentOutboxEvent loaded = newRow();
        loaded.setTraceContext(REQUEST_CONTEXT);
        ReflectionTestUtils.setField(loaded, "persisted", true);   // @PostLoad 가 하는 일
        when(traceContext.capture()).thenReturn("traceparent=00-ffffffffffffffffffffffffffffffff-1111111111111111-01\n");

        loaded.setOutboxStatus(OutboxStatus.COMPLETED);
        helper.save(loaded);

        assertThat(loaded.getTraceContext()).isEqualTo(REQUEST_CONTEXT);
    }

    @Test
    @DisplayName("🔴 스케줄러는 행에 남은 문맥 아래에서 보낸다 — 보내는 일 자체는 그대로다")
    @SuppressWarnings("unchecked")
    void relayResumesTheStoredContext() {
        PaymentOutboxEvent row = newRow();
        row.setTraceContext(REQUEST_CONTEXT);
        PaymentOutboxHelper claiming = mock(PaymentOutboxHelper.class);
        when(claiming.claimPaymentOutboxEvents(eq(OutboxStatus.STARTED), any())).thenReturn(List.of(row));
        PaymentRequestKafkaPublisher publisher = mock(PaymentRequestKafkaPublisher.class);
        when(publisher.publish(eq(row), any())).thenReturn(true);
        // resume 은 받은 일을 그대로 실행한다 — 문맥을 되살리는 부분은 common 에서 검증했다.
        when(traceContext.resume(anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());

        PaymentOutboxScheduler scheduler = new PaymentOutboxScheduler(
                claiming, publisher,
                mock(com.project.order_service.ports.output.message.publisher.OrderStatusKafkaPublisher.class), traceContext);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        scheduler.processOutboxMessage();

        verify(traceContext).resume(eq(REQUEST_CONTEXT), eq("outbox relay payment-request"), any(Supplier.class));
        verify(publisher).publish(eq(row), any());
    }

    @Test
    @DisplayName("추적이 없는 환경(NOOP)에서도 행은 그대로 저장된다")
    void worksWithoutTracing() {
        PaymentOutboxHelper plain = new PaymentOutboxHelper(repository, new ObjectMapper(), OutboxTraceContext.NOOP, OutboxRelayTrigger.DISABLED);
        PaymentOutboxEvent row = newRow();

        plain.save(row);

        assertThat(row.getTraceContext()).isNull();
        verify(repository).save(row);
        verify(traceContext, never()).capture();
    }
}
