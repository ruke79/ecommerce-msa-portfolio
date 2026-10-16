package com.project.pay_service.ports.input.message.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.outbox.OutboxTraceContext;

/**
 * 🐛 결제 요청까지만 추적이 이어지고 결제 응답부터 끊겼다 (docs/51 §4f).
 *
 * <p>이 리스너는 <b>배치</b>라 Spring Kafka 의 컨슈머 추적이 걸리지 않는다. 결제 응답 아웃박스 행을
 * 쓰는 순간 현재 추적이 없었고, 결제 응답 릴레이가 새 추적으로 시작했다. 이제 레코드마다 <b>자기
 * 헤더</b>로 추적을 이어받은 안에서 결제를 처리한다. 헤더가 실제 스프링 배치 변환기로 어떻게 오는지와
 * 이어받은 안에서 쓴 행에 원래 traceId 가 남는지는 common 의 {@code OutboxTraceContextTest} 가 본다.
 */
@DisplayName("pay-service — 결제 요청 배치 리스너의 추적 이어받기")
class PaymentRequestTraceContinuationTest {

    @Test
    @DisplayName("🔴 레코드마다 그 레코드의 헤더로 이어받은 안에서 결제를 처리한다")
    @SuppressWarnings("unchecked")
    void eachRecordRunsUnderItsOwnHeaders() {
        PaymentRequestMessageListener messages = mock(PaymentRequestMessageListener.class);
        OutboxTraceContext trace = mock(OutboxTraceContext.class);
        List<Map<String, ?>> seen = new ArrayList<>();
        when(trace.continueFrom(any(), eq("receive payment-request"), any(Supplier.class))).thenAnswer(i -> {
            seen.add(i.getArgument(0));
            return ((Supplier<Object>) i.getArgument(2)).get();
        });

        PaymentRequest first = PaymentRequest.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build();
        PaymentRequest second = PaymentRequest.builder().orderId(2L).paymentStatus(PaymentStatus.CANCELED).build();
        List<Map<String, Object>> headers = List.of(
                Map.of("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1111111111111111-01".getBytes()),
                Map.of("traceparent", "00-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-2222222222222222-01".getBytes()));

        // 배치 변환 헤더로 올 때 — 레코드 어댑터로 올 때는 PaymentRequestListenerAdapterTraceTest 가 본다.
        new PaymentRequestKafkaListener(messages, trace).receive(List.of(first, second),
                Map.of(org.springframework.kafka.support.KafkaHeaders.BATCH_CONVERTED_HEADERS, headers));

        assertThat(seen).containsExactly(headers.get(0), headers.get(1));
        InOrder order = inOrder(messages);
        order.verify(messages).completePayment(first);
        order.verify(messages).cancelPayment(second);
    }

    /** 헤더 목록이 없어도(변환기가 바뀌었거나 옛 메시지) 결제는 그대로 처리된다 — 추적은 부가 정보다. */
    @Test
    @DisplayName("헤더 목록이 없어도 결제는 처리된다")
    @SuppressWarnings("unchecked")
    void worksWithoutBatchHeaders() {
        PaymentRequestMessageListener messages = mock(PaymentRequestMessageListener.class);
        OutboxTraceContext trace = mock(OutboxTraceContext.class);
        when(trace.continueFrom(isNull(), any(), any(Supplier.class)))
                .thenAnswer(i -> ((Supplier<Object>) i.getArgument(2)).get());
        PaymentRequest request = PaymentRequest.builder().orderId(1L).paymentStatus(PaymentStatus.COMPLETED).build();

        new PaymentRequestKafkaListener(messages, trace).receive(List.of(request), null);

        inOrder(messages).verify(messages).completePayment(request);
    }
}
