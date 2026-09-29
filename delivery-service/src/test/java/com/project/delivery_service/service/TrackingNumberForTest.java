package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 송장번호 — 테스트 택배사는 비워 두면 만들어 준다. */
@DisplayName("송장번호 정하기")
class TrackingNumberForTest {

    @Test
    @DisplayName("🔴 테스트 택배사 + 빈 번호 → SIM-<주문>-<상자>(정상 진행 각본)")
    void simulatedBlankGetsGenerated() {
        assertThat(DeliveryService.trackingNumberFor("SIMULATED", "", 891581244712654048L, 1))
                .isEqualTo("SIM-891581244712654048-1");
        assertThat(DeliveryService.trackingNumberFor("SIMULATED", null, 7L, 2)).isEqualTo("SIM-7-2");
    }

    @Test
    @DisplayName("적은 번호는 그대로(앞뒤 공백만 뺀다) — 각본을 고르려면 SIMFAIL-·SIMEXC- 를 적는다")
    void givenNumberKept() {
        assertThat(DeliveryService.trackingNumberFor("SIMULATED", " SIMFAIL-1 ", 7L, 1)).isEqualTo("SIMFAIL-1");
        assertThat(DeliveryService.trackingNumberFor("KOREA_POST", "1234567890", 7L, 1)).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("실제 택배사는 번호가 있어야 한다")
    void realCarrierNeedsNumber() {
        assertThat(DeliveryService.trackingNumberFor("KOREA_POST", " ", 7L, 1)).isNull();
    }
}
