package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 순서만으로는 안 되는 전이 — 예외 뒤의 정상 스캔, 부재 뒤의 재배달 출발. 그리고 택배사 문구의 길이.
 *
 * <p>[JA] 順序だけでは扱えない遷移 — 例外の後の正常スキャン、不在の後の再配達出発。そして宅配会社の文言の長さ。
 */
@DisplayName("배송 상태 전이 · 이벤트 길이")
class DeliveryTransitionTest {

    @Test
    @DisplayName("🔴 '파손' 스캔(EXCEPTION) 뒤에 정상 배달되면 DELIVERED 가 된다 — 예전엔 EXCEPTION 에 고정")
    void exceptionCanRecover() {
        assertThat(DeliveryStatus.DELIVERED.isProgressFrom(DeliveryStatus.EXCEPTION)).isTrue();
        assertThat(DeliveryStatus.IN_TRANSIT.isProgressFrom(DeliveryStatus.EXCEPTION)).isTrue();
        assertThat(DeliveryStatus.OUT_FOR_DELIVERY.isProgressFrom(DeliveryStatus.EXCEPTION)).isTrue();
        assertThat(DeliveryStatus.PICKED_UP.isProgressFrom(DeliveryStatus.EXCEPTION)).as("집화 전으로는 돌아가지 않는다").isFalse();
        assertThat(DeliveryStatus.EXCEPTION.isProgressFrom(DeliveryStatus.EXCEPTION)).as("같은 상태는 전이가 아니다").isFalse();
    }

    @Test
    @DisplayName("🔴 부재(ATTEMPT_FAILED) 뒤의 재배달 출발(OUT_FOR_DELIVERY)은 전이다 — 예전엔 무시")
    void redeliveryAfterAttemptFailed() {
        assertThat(DeliveryStatus.OUT_FOR_DELIVERY.isProgressFrom(DeliveryStatus.ATTEMPT_FAILED)).isTrue();
        assertThat(DeliveryStatus.IN_TRANSIT.isProgressFrom(DeliveryStatus.ATTEMPT_FAILED)).isFalse();
        assertThat(DeliveryStatus.IN_TRANSIT.isProgressFrom(DeliveryStatus.DELIVERED)).as("끝난 배송은 그대로").isFalse();
    }

    @Test
    @DisplayName("상자에서 — 예외 뒤 배달 완료가 반영되고 폴링이 멈춘다")
    void shipmentRecoversFromException() {
        Delivery d = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        Shipment s = d.addShipment(false);
        s.assignTracking("SIMULATED", "SIM-1", true);
        s.advanceTo(DeliveryStatus.EXCEPTION, LocalDateTime.now());

        assertThat(s.advanceTo(DeliveryStatus.DELIVERED, LocalDateTime.now())).isTrue();
        assertThat(s.getNextPollAt()).isNull();
        assertThat(d.overallStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("🔴 택배사 문구가 컬럼보다 길면 자른다 — 예전엔 길이 초과 한 건이 배치를 통째로 되돌렸다")
    void eventFieldsAreFitted() {
        String location = "가".repeat(200);
        String raw = "나".repeat(200);
        DeliveryEvent e = new DeliveryEvent(null, DeliveryStatus.IN_TRANSIT, LocalDateTime.of(2026, 10, 15, 9, 0),
                location, "다".repeat(500), raw, null);

        assertThat(e.getLocation()).hasSize(120);
        assertThat(e.getDescription()).hasSize(300);
        assertThat(e.getRawStatus()).hasSize(120);
        assertThat(e.getDedupKey()).as("중복 제거 키는 원문으로 — 조회 쪽과 같게")
                .isEqualTo(DeliveryEvent.dedupKey(LocalDateTime.of(2026, 10, 15, 9, 0), raw, location));
    }
}
