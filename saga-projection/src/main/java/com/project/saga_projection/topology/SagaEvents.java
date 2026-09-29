package com.project.saga_projection.topology;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.SagaPhase;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;

/**
 * 입력 레코드 하나를 상태 <b>델타</b>로 바꾼다. 요청과 응답을 같은 타입으로 만들어 두 스트림을 합치고
 * 하나의 집계로 접기 위해서다 — 델타도 {@link OrderSagaState} 다(스키마 하나로 끝난다).
 *
 * <p>요청 델타는 {@code requestCount=1, responseCount=0}, 응답 델타는 그 반대다. 리듀서는 이 둘로
 * 종류를 구분한다. 토큰({@code authorizationToken})은 <b>싣지 않는다</b> — 프로젝션은 조회 API 로
 * 노출되므로 자격증명이 들어가면 안 된다.
 */
public final class SagaEvents {

    private final Clock clock;

    public SagaEvents(Clock clock) {
        this.clock = clock;
    }

    public OrderSagaState fromRequest(PaymentRequest r) {
        Instant now = clock.instant();
        Instant requestedAt = r.getCreatedAt() == null ? now : r.getCreatedAt().toInstant(ZoneOffset.UTC);
        return OrderSagaState.newBuilder()
                .setOrderId(r.getOrderId())
                .setPhase(SagaPhase.REQUESTED)
                .setCustomerId(r.getCustomerId())
                .setAmounts(r.getAmounts() == null ? null : r.getAmounts().toPlainString())
                .setCouponName(r.getCouponName())
                .setRequestedAt(requestedAt)
                .setRequestCount(1)
                .setResponseCount(0)
                .setUpdatedAt(now)
                .build();
    }

    public OrderSagaState fromResponse(PaymentResponse r) {
        Instant now = clock.instant();
        return OrderSagaState.newBuilder()
                .setOrderId(r.getOrderId())
                .setPhase(phaseOf(r.getPaymentStatus()))
                .setCustomerId(r.getCustomerId())
                .setAmounts(r.getAmounts() == null ? null : r.getAmounts().toPlainString())
                .setCouponName(r.getCouponName())
                .setPaymentId(r.getPaymentId())
                .setPaymentStatus(r.getPaymentStatus() == null ? null : r.getPaymentStatus().name())
                .setRespondedAt(now)
                .setRequestCount(0)
                .setResponseCount(1)
                .setUpdatedAt(now)
                .build();
    }

    /** 응답의 결제 상태 → 단계. 상태가 비어 있으면 FAILED — pay 는 종료 상태만 보내므로 빈 값은 그 자체가 이상이다. */
    static SagaPhase phaseOf(PaymentStatus status) {
        if (status == null) {
            return SagaPhase.FAILED;
        }
        return switch (status) {
            case COMPLETED -> SagaPhase.COMPLETED;
            case CANCELED -> SagaPhase.CANCELED;
            case FAILED -> SagaPhase.FAILED;
        };
    }

    static SagaPhase phaseOf(String statusName) {
        if (statusName == null) {
            return SagaPhase.FAILED;
        }
        try {
            return phaseOf(PaymentStatus.valueOf(statusName));
        } catch (IllegalArgumentException e) {
            return SagaPhase.FAILED;
        }
    }
}
