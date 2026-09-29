package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🐛 송장을 등록하면 주문은 DISPATCHED 가 되고 "발송되었습니다" 알림이 나가는데, 배송 추적의
 * 진행 바는 <b>주문 완료</b>에 서 있었다 — {@code INFO_RECEIVED} 의 단계가 {@code ORDERED} 였다.
 * 같은 화면에서 주문 상태와 진행 바가 서로 다른 말을 했다.
 */
@DisplayName("배송 상태 → 화면 단계")
class DeliveryStageTest {

    @Test
    @DisplayName("🔴 송장 등록(INFO_RECEIVED)은 '발송' 단계다 — 주문의 DISPATCHED 와 같은 말")
    void infoReceivedIsShipped() {
        assertThat(DeliveryStatus.INFO_RECEIVED.stage()).isEqualTo(DeliveryStatus.Stage.SHIPPED);
        // 그래도 실물은 아직 판매자에게 있으므로 주소 변경·취소는 된다.
        assertThat(DeliveryStatus.INFO_RECEIVED.isBeforeDispatch()).isTrue();
    }

    @Test
    @DisplayName("송장 전(PENDING)만 '주문 완료' 단계다")
    void onlyPendingIsOrdered() {
        for (DeliveryStatus s : DeliveryStatus.values()) {
            assertThat(s.stage() == DeliveryStatus.Stage.ORDERED).as(s.name()).isEqualTo(s == DeliveryStatus.PENDING);
        }
    }
}
