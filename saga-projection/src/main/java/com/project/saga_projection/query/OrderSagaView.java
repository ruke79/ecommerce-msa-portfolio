package com.project.saga_projection.query;

import java.time.Instant;

import com.project.common.avro.OrderSagaState;

/**
 * {@code GET /api/saga/{orderId}} 응답. Avro 생성 클래스를 그대로 JSON 으로 내면 {@code schema}·
 * {@code specificData} 같은 내부 필드가 새어 나오므로 평범한 레코드로 옮긴다.
 *
 * @param stale 이 인스턴스가 활성 태스크가 아니라 대기 복제본에서 답했다 — 변경 로그만큼 늦을 수 있다.
 */
public record OrderSagaView(
        long orderId,
        String phase,
        Long customerId,
        String amounts,
        String couponName,
        Long paymentId,
        String paymentStatus,
        Instant requestedAt,
        Instant respondedAt,
        int requestCount,
        int responseCount,
        Instant updatedAt,
        boolean stale) {

    public static OrderSagaView of(OrderSagaState s, boolean stale) {
        return new OrderSagaView(
                s.getOrderId(),
                s.getPhase().name(),
                s.getCustomerId(),
                s.getAmounts(),
                s.getCouponName(),
                s.getPaymentId(),
                s.getPaymentStatus(),
                s.getRequestedAt(),
                s.getRespondedAt(),
                s.getRequestCount(),
                s.getResponseCount(),
                s.getUpdatedAt(),
                stale);
    }
}
