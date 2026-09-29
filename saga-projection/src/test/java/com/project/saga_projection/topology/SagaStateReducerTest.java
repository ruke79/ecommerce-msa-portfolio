package com.project.saga_projection.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.avro.OrderSagaState;
import com.project.common.avro.SagaPhase;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;

@DisplayName("SagaStateReducer · 순수 접기 규칙")
class SagaStateReducerTest {

    private static final Instant T0 = Instant.parse("2026-09-11T00:00:00Z");
    private final SagaEvents at0 = new SagaEvents(Clock.fixed(T0, ZoneOffset.UTC));
    private final SagaEvents at1 = new SagaEvents(Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));

    private static PaymentRequest request(long orderId) {
        return PaymentRequest.builder().orderId(orderId).customerId(7L).amounts(new BigDecimal("59.98"))
                .couponName("WELCOME").createdAt(LocalDateTime.of(2026, 9, 10, 23, 59)).build();
    }

    private static PaymentResponse response(long orderId, PaymentStatus status) {
        return PaymentResponse.builder().orderId(orderId).paymentId(99L).customerId(7L).paymentStatus(status).build();
    }

    @Test
    @DisplayName("요청 → 응답(COMPLETED): REQUESTED 를 거쳐 COMPLETED, 요청 필드는 요청의 것")
    void requestThenResponse() {
        OrderSagaState s1 = SagaStateReducer.apply(null, at0.fromRequest(request(1)));
        assertThat(s1.getPhase()).isEqualTo(SagaPhase.REQUESTED);
        assertThat(s1.getRequestedAt()).isEqualTo(Instant.parse("2026-09-10T23:59:00Z"));
        assertThat(s1.getAmounts()).isEqualTo("59.98");

        OrderSagaState s2 = SagaStateReducer.apply(s1, at1.fromResponse(response(1, PaymentStatus.COMPLETED)));
        assertThat(s2.getPhase()).isEqualTo(SagaPhase.COMPLETED);
        assertThat(s2.getPaymentId()).isEqualTo(99L);
        assertThat(s2.getPaymentStatus()).isEqualTo("COMPLETED");
        assertThat(s2.getRequestCount()).isEqualTo(1);
        assertThat(s2.getResponseCount()).isEqualTo(1);
        assertThat(s2.getRespondedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(s2.getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(s2.getCouponName()).isEqualTo("WELCOME");
    }

    @Test
    @DisplayName("응답이 먼저 오면 ORPHAN_RESPONSE, 요청이 뒤따르면 응답의 단계로")
    void orphanResponseThenRequest() {
        OrderSagaState s1 = SagaStateReducer.apply(null, at0.fromResponse(response(2, PaymentStatus.FAILED)));
        assertThat(s1.getPhase()).isEqualTo(SagaPhase.ORPHAN_RESPONSE);
        assertThat(s1.getRequestCount()).isZero();

        OrderSagaState s2 = SagaStateReducer.apply(s1, at1.fromRequest(request(2)));
        assertThat(s2.getPhase()).isEqualTo(SagaPhase.FAILED);
        assertThat(s2.getRequestCount()).isEqualTo(1);
        assertThat(s2.getResponseCount()).isEqualTo(1);
        assertThat(s2.getAmounts()).as("응답에 금액이 없었으니 요청의 금액으로 채워진다").isEqualTo("59.98");
    }

    @Test
    @DisplayName("종료 상태 뒤의 요청 재발행은 단계를 되돌리지 않고 요청 수만 센다")
    void duplicateRequestAfterTerminalKeepsPhase() {
        OrderSagaState done = SagaStateReducer.apply(
                SagaStateReducer.apply(null, at0.fromRequest(request(3))),
                at0.fromResponse(response(3, PaymentStatus.CANCELED)));
        OrderSagaState after = SagaStateReducer.apply(done, at1.fromRequest(request(3)));
        assertThat(after.getPhase()).isEqualTo(SagaPhase.CANCELED);
        assertThat(after.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("응답이 두 번 오면 나중 것이 이긴다 — pay 의 최종 판단")
    void laterResponseWins() {
        OrderSagaState s = SagaStateReducer.apply(null, at0.fromRequest(request(4)));
        s = SagaStateReducer.apply(s, at0.fromResponse(response(4, PaymentStatus.FAILED)));
        s = SagaStateReducer.apply(s, at1.fromResponse(response(4, PaymentStatus.COMPLETED)));
        assertThat(s.getPhase()).isEqualTo(SagaPhase.COMPLETED);
        assertThat(s.getResponseCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("updatedAt 은 뒤로 가지 않는다 — 늦게 도착한 옛 델타가 시각을 되돌리지 않는다")
    void updatedAtNeverGoesBackwards() {
        OrderSagaState s = SagaStateReducer.apply(null, at1.fromRequest(request(5)));
        s = SagaStateReducer.apply(s, at0.fromResponse(response(5, PaymentStatus.COMPLETED)));
        assertThat(s.getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("상태가 없는 응답은 FAILED 로 — pay 는 종료 상태만 보내므로 빈 값은 그 자체가 이상이다")
    void nullStatusIsFailed() {
        assertThat(SagaEvents.phaseOf((PaymentStatus) null)).isEqualTo(SagaPhase.FAILED);
        assertThat(SagaEvents.phaseOf("NOPE")).isEqualTo(SagaPhase.FAILED);
        assertThat(SagaEvents.phaseOf("CANCELED")).isEqualTo(SagaPhase.CANCELED);
    }

    @Test
    @DisplayName("델타에 토큰이 실리지 않는다 — 프로젝션은 조회 API 로 노출된다")
    void tokenNeverEntersState() {
        OrderSagaState delta = at0.fromRequest(request(6));
        assertThat(delta.getSchema().getField("authorizationToken")).isNull();
        assertThat(delta.toString()).doesNotContain("secret");
    }
}
