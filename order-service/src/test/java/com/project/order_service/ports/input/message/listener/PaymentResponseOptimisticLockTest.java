package com.project.order_service.ports.input.message.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxTraceContext;
import com.project.order_service.saga.OrderSaga;

/**
 * 결제 응답의 낙관적 잠금 충돌은 다시 시도한다. 🐛 예전에는 "다른 스레드가 끝냈다" 고 삼켜, 충돌의 상대가 결제 확정이 아니면 결제 응답이
 * 사라졌다(고객은 돈을 냈는데 주문은 결제 전).
 *
 * <p>[JA] 決済応答の楽観ロック衝突は再試行する。以前は握りつぶし、決済応答が消えることがあった。
 */
@DisplayName("order-service — 결제 응답의 낙관적 잠금 충돌")
class PaymentResponseOptimisticLockTest {

    @Test
    @DisplayName("🔴 낙관적 잠금 충돌은 그 레코드의 실패로 알린다(BatchListenerFailedException) — 재시도 · 실패하면 DLT 와 경보")
    void optimisticLockIsRetried() {
        OrderSaga saga = mock(OrderSaga.class);
        doThrow(new OptimisticLockingFailureException("busy")).when(saga).process(any());
        PaymentResponseKafkaListener listener = new PaymentResponseKafkaListener(new PaymentResponseMessageListener(saga),
                OutboxTraceContext.NOOP);
        PaymentResponse completed = PaymentResponse.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build();

        assertThatThrownBy(() -> listener.received(List.of(completed), Map.of()))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BatchListenerFailedException) e).getIndex()).isZero());
    }
}
