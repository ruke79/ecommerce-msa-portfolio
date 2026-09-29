package com.project.saga_projection.topology;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.SagaPhase;

/**
 * 집계 함수: (지금까지의 상태, 델타) → 새 상태. 순수 함수라 TopologyTestDriver 없이도 검증한다.
 *
 * <p>규칙:
 * <ul>
 *   <li>요청 → 상태 없음: {@code REQUESTED}.</li>
 *   <li>응답 → 상태 없음: {@code ORPHAN_RESPONSE}. {@code order} 와 {@code payment} 는 별개 토픽이라 소비
 *       순서가 독립이다 — 응답이 먼저 보이는 것은 정상 범위다. 요청이 뒤이어 오면 응답의 단계로 옮긴다.</li>
 *   <li>요청 → 이미 있음: 단계는 그대로(종료 상태를 요청 재발행이 되돌리면 안 된다), 요청 수만 +1.
 *       단, {@code ORPHAN_RESPONSE} 였으면 이제 짝이 맞았으니 응답의 단계로.</li>
 *   <li>응답 → 이미 있음: 단계 = 응답의 단계, 응답 수 +1. 늦게 온 응답이 이긴다 — pay 가 같은 주문에
 *       두 번 답했다면 나중 것이 pay 의 최종 판단이다.</li>
 *   <li>식별 필드(customerId·amounts·couponName)는 비어 있을 때만 채운다. 요청이 진실이고 응답은 보조다.</li>
 * </ul>
 */
public final class SagaStateReducer {

    private SagaStateReducer() {
    }

    public static OrderSagaState apply(OrderSagaState current, OrderSagaState delta) {
        boolean isRequest = delta.getRequestCount() > 0;

        if (current == null) {
            OrderSagaState first = OrderSagaState.newBuilder(delta).build();
            if (!isRequest) {
                first.setPhase(SagaPhase.ORPHAN_RESPONSE);
            }
            return first;
        }

        OrderSagaState next = OrderSagaState.newBuilder(current).build();
        if (next.getCustomerId() == null) next.setCustomerId(delta.getCustomerId());
        if (next.getAmounts() == null) next.setAmounts(delta.getAmounts());
        if (next.getCouponName() == null) next.setCouponName(delta.getCouponName());

        if (isRequest) {
            next.setRequestCount(current.getRequestCount() + delta.getRequestCount());
            if (next.getRequestedAt() == null) next.setRequestedAt(delta.getRequestedAt());
            if (current.getPhase() == SagaPhase.ORPHAN_RESPONSE) {
                next.setPhase(SagaEvents.phaseOf(current.getPaymentStatus()));
            }
        } else {
            next.setResponseCount(current.getResponseCount() + delta.getResponseCount());
            next.setPaymentId(delta.getPaymentId());
            next.setPaymentStatus(delta.getPaymentStatus());
            next.setRespondedAt(delta.getRespondedAt());
            next.setPhase(current.getRequestCount() == 0 ? SagaPhase.ORPHAN_RESPONSE : delta.getPhase());
        }

        if (delta.getUpdatedAt() != null
                && (next.getUpdatedAt() == null || delta.getUpdatedAt().isAfter(next.getUpdatedAt()))) {
            next.setUpdatedAt(delta.getUpdatedAt());
        }
        return next;
    }
}
