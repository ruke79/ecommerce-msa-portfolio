package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🐛 <b>회귀 대상 — 시뮬레이터로는 배송 완료를 볼 수 없었다.</b>
 *
 * <p>접수(INFO_RECEIVED) 뒤 기본 조회 주기가 6시간이다. 시뮬레이터는 1분마다 단계를 밟는데
 * 6시간 뒤에 한 번 물으면 이력 전체가 한꺼번에 와서 곧장 배달 완료로 뛴다 — 집화·이동·배달
 * 출발과 그 알림이 전부 사라진다. 택배사가 알려 준 갱신 주기가 있으면 그것과 기본 주기 중
 * 짧은 쪽을 쓴다.
 */
@DisplayName("상자 — 다음 조회 시각과 택배사 갱신 주기")
class ShipmentPollCapTest {

    private static Shipment tracked(DeliveryStatus status) {
        Delivery d = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        Shipment s = d.addShipment(false);
        s.assignTracking("SIMULATED", "SIM-1", true);
        if (status != DeliveryStatus.INFO_RECEIVED) {
            s.advanceTo(status, LocalDateTime.now(ZoneOffset.UTC));
        }
        return s;
    }

    private static Duration untilNextPoll(Shipment s) {
        return Duration.between(LocalDateTime.now(ZoneOffset.UTC), s.getNextPollAt());
    }

    @Test
    @DisplayName("🔴 택배사 주기가 기본보다 짧으면 그것을 쓴다 — 접수 뒤 6시간이 아니라 1분")
    void capShortensTheInterval() {
        Shipment s = tracked(DeliveryStatus.INFO_RECEIVED);

        s.recordSyncSuccess(Duration.ofMinutes(1));

        assertThat(untilNextPoll(s).toSeconds()).isBetween(55L, 65L);
    }

    @Test
    @DisplayName("상한이 없으면 예전 그대로 상태별 기본 주기다")
    void noCapKeepsTheDefault() {
        Shipment s = tracked(DeliveryStatus.INFO_RECEIVED);

        s.recordSyncSuccess();

        assertThat(untilNextPoll(s).toSeconds()).isBetween(21595L, 21605L);
    }

    /** 상한은 줄이는 데만 쓴다. 실제 택배사가 큰 값을 줘도 배달 출발의 30분이 늘어나면 안 된다. */
    @Test
    @DisplayName("택배사 주기가 기본보다 길면 기본을 지킨다 — 늘리는 데는 쓰지 않는다")
    void capNeverLengthens() {
        Shipment s = tracked(DeliveryStatus.OUT_FOR_DELIVERY);

        s.recordSyncSuccess(Duration.ofHours(6));

        assertThat(untilNextPoll(s).toSeconds()).isBetween(1795L, 1805L);
    }

    @Test
    @DisplayName("끝난 배송은 상한이 있어도 다시 조회하지 않는다")
    void terminalStaysOff() {
        Shipment s = tracked(DeliveryStatus.DELIVERED);

        s.recordSyncSuccess(Duration.ofMinutes(1));

        assertThat(s.getNextPollAt()).isNull();
    }

    @Test
    @DisplayName("0 이나 음수 상한은 무시한다")
    void nonsenseCapIsIgnored() {
        Shipment s = tracked(DeliveryStatus.IN_TRANSIT);

        s.recordSyncSuccess(Duration.ZERO);
        assertThat(untilNextPoll(s).toSeconds()).isBetween(10795L, 10805L);

        s.recordSyncSuccess(Duration.ofMinutes(-5));
        assertThat(untilNextPoll(s).toSeconds()).isBetween(10795L, 10805L);
    }
}
