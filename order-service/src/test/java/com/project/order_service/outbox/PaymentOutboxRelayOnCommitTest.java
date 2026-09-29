package com.project.order_service.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxRelayTrigger;
import com.project.common.outbox.OutboxStatus;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.outbox.model.PaymentOutboxEvent;
import com.project.order_service.outbox.repository.jpa.PaymentOutboxRepository;

/**
 * 결제 요청 행을 쓰면 커밋 직후 릴레이를 예약한다 (docs/56).
 *
 * <p>예전에는 행을 쓰고 끝이었다 — 결제 요청은 다음 폴링(최대 10초)까지 DB 에 앉아 있었다. 사가의
 * 첫 번째 대기다. 두 번째는 pay-service 의 결제 응답이다({@code OrderOutboxRelayOnCommitTest}).
 */
@DisplayName("order-service — 결제 요청 아웃박스의 즉시 릴레이")
class PaymentOutboxRelayOnCommitTest {

    private OutboxRelayTrigger trigger;
    private PaymentOutboxHelper helper;

    @BeforeEach
    void setUp() {
        PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class);
        when(repository.save(any(PaymentOutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        trigger = mock(OutboxRelayTrigger.class);
        helper = new PaymentOutboxHelper(repository, new ObjectMapper(), OutboxTraceContext.NOOP, trigger);
    }

    @Test
    @DisplayName("🔴 발행 대기(STARTED) 결제 요청을 쓰면 릴레이를 예약한다")
    void startedRowSchedulesRelay() {
        helper.savePaymentOutbox(PaymentRequest.builder().orderId(1L).build(), OutboxStatus.STARTED);

        verify(trigger).afterCommit();
    }

    @Test
    @DisplayName("발행 대기가 아닌 행은 예약하지 않는다 — 보낼 것이 없다")
    void nonStartedRowDoesNot() {
        helper.savePaymentOutbox(PaymentRequest.builder().orderId(1L).build(), OutboxStatus.COMPLETED);

        verify(trigger, never()).afterCommit();
    }

    @Test
    @DisplayName("🔴 상태 갱신(save)은 예약하지 않는다 — 릴레이가 자기 자신을 다시 부르면 안 된다")
    void statusUpdateDoesNot() {
        PaymentOutboxEvent row = new PaymentOutboxEvent("1", "Order", "payment-request", "{}", OutboxStatus.STARTED);

        helper.save(row);

        verify(trigger, never()).afterCommit();
    }
}
