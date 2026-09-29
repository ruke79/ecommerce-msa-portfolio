package com.project.pay_service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.outbox.repository.jpa.OrderOutboxRepository;

/**
 * 결제 응답 행을 쓰면 커밋 직후 릴레이를 예약한다 (docs/56).
 *
 * <p>결제 사가의 두 번째 폴링 대기다 — 예전에는 결제 응답이 다음 폴링(최대 10초)까지 DB 에 있었다.
 */
@DisplayName("pay-service — 결제 응답 아웃박스의 즉시 릴레이")
class OrderOutboxRelayOnCommitTest {

    private OutboxRelayTrigger trigger;
    private OrderOutboxHelper helper;

    @BeforeEach
    void setUp() {
        OrderOutboxRepository repository = mock(OrderOutboxRepository.class);
        when(repository.save(any(OrderOutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        trigger = mock(OutboxRelayTrigger.class);
        helper = new OrderOutboxHelper(repository, new ObjectMapper(), OutboxTraceContext.NOOP, trigger);
    }

    private static PaymentResponse response() {
        return PaymentResponse.builder().orderId(1L).paymentId(2L)
                .amounts(new BigDecimal("59.98")).paymentStatus(PaymentStatus.COMPLETED).build();
    }

    @Test
    @DisplayName("🔴 발행 대기(STARTED) 결제 응답을 쓰면 릴레이를 예약한다")
    void startedRowSchedulesRelay() {
        helper.saveOrderOutbox(response(), OutboxStatus.STARTED);

        verify(trigger).afterCommit();
    }

    @Test
    @DisplayName("🔴 상태 갱신은 예약하지 않는다 — 릴레이가 자기 자신을 다시 부르면 안 된다")
    void statusUpdateDoesNot() {
        OrderOutboxEvent row = new OrderOutboxEvent("1", "Payment", "payment-response", "{}", OutboxStatus.STARTED);

        helper.updateOutboxStatus(row, OutboxStatus.COMPLETED);
        helper.save(row);

        verify(trigger, never()).afterCommit();
    }
}
