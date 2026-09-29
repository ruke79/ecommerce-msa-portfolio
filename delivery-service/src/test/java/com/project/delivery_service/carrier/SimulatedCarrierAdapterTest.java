package com.project.delivery_service.carrier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.delivery_service.domain.DeliveryStatus;

/**
 * 시뮬레이터 — 실제 택배사 없이 전 경로를 돌리기 위한 어댑터.
 *
 * <p>시계를 주입해 몇 시간을 순식간에 지나가게 한다.
 */
@DisplayName("시뮬레이터 택배사")
class SimulatedCarrierAdapterTest {

    private final AtomicReference<LocalDateTime> now =
            new AtomicReference<>(LocalDateTime.of(2026, 7, 28, 9, 0));

    private final SimulatedCarrierAdapter adapter =
            new SimulatedCarrierAdapter(Duration.ofHours(1), now::get);

    private void advanceHours(int hours) {
        now.updateAndGet(t -> t.plusHours(hours));
    }

    @Test
    @DisplayName("갱신 주기로 단계 간격을 알려 준다 — 폴러가 그 리듬을 따른다")
    void pollIntervalCapIsTheStep() {
        assertThat(adapter.pollIntervalCap()).contains(Duration.ofHours(1));
    }

    @Test
    @DisplayName("시간이 흐르면 상태가 실제로 진행한다")
    void 시간에_따라_진행() {
        TrackingResult first = adapter.track("SIM-1", false);
        assertThat(first.events()).hasSize(1);
        assertThat(first.events().get(0).status()).isEqualTo(DeliveryStatus.INFO_RECEIVED);

        advanceHours(4);
        TrackingResult later = adapter.track("SIM-1", false);

        assertThat(later.events()).hasSize(5);
        assertThat(later.events().get(4).status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("매 조회마다 전체 이력을 돌려준다 — 그래서 중복 제거가 필요하다")
    void 전체_이력을_반복해서_준다() {
        adapter.track("SIM-2", false);
        advanceHours(2);

        TrackingResult a = adapter.track("SIM-2", false);
        TrackingResult b = adapter.track("SIM-2", false);

        assertThat(a.events()).hasSize(3);
        assertThat(b.events())
                .as("실제 택배사 API 도 이렇게 동작한다 — 저장 전에 걸러야 한다")
                .hasSize(3);
    }

    @Test
    @DisplayName("국제 배송은 통관 단계를 거친다")
    void 국제_배송_시나리오() {
        adapter.track("SIMINTL-1", true);
        advanceHours(6);

        TrackingResult result = adapter.track("SIMINTL-1", true);

        assertThat(result.events()).extracting(TrackingEvent::status)
                .contains(DeliveryStatus.CUSTOMS_HOLD, DeliveryStatus.CUSTOMS_CLEARED);
    }

    @Test
    @DisplayName("배달 실패 시나리오 — 해피패스만 검증하면 운영에서 막힌다")
    void 배달_실패_시나리오() {
        adapter.track("SIMFAIL-1", false);
        advanceHours(6);

        TrackingResult result = adapter.track("SIMFAIL-1", false);

        assertThat(result.events()).extracting(TrackingEvent::status)
                .contains(DeliveryStatus.ATTEMPT_FAILED, DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("예외 시나리오는 EXCEPTION 에서 멈춘다")
    void 예외_시나리오() {
        adapter.track("SIMEXC-1", false);
        advanceHours(5);

        TrackingResult result = adapter.track("SIMEXC-1", false);
        assertThat(result.events().get(result.events().size() - 1).status())
                .isEqualTo(DeliveryStatus.EXCEPTION);
    }

    @Test
    @DisplayName("모르는 송장번호는 NOT_FOUND")
    void 없는_송장() {
        assertThat(adapter.track("SIMNONE-1", false).status())
                .isEqualTo(TrackingResult.Status.NOT_FOUND);
    }
}
