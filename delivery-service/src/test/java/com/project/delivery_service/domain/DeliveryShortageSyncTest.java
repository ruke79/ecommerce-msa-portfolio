package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재고 부족이 배송 생성보다 늦게 적혔을 때 — 있는 것만 보내고 없는 것은 그 품목만 취소(대형 EC 방식, 5단계 15).
 * [JA] 在庫不足が配送作成より遅れて記録されたとき — あるものだけ送り、ないものはその品目だけ取り消す。
 */
@DisplayName("배송 수량 다시 맞추기 — 재고 부족")
class DeliveryShortageSyncTest {

    private static Delivery delivery() {
        return Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동").recipientPhone("+821012345678")
                .postalCode("06236").addressLine1("서울 강남구 테헤란로 1").city("서울").country("KR").build();
    }

    private static ShipmentItem item(Shipment s, long orderProductId, int qty) {
        ShipmentItem i = new ShipmentItem(s, orderProductId, orderProductId * 10, "p" + orderProductId, null, qty, "SKU-" + orderProductId);
        s.addItem(i);
        return i;
    }

    @Test
    @DisplayName("🔴 발송 전 상자 — 보낼 수로 줄이고 0 이 된 품목은 뺀다 · 다른 품목은 그대로 · 다시 불러도 그대로(멱등)")
    void reducesPendingShipment() {
        Delivery d = delivery();
        Shipment s = d.addShipment(false);
        ShipmentItem tee = item(s, 101, 3);
        item(s, 102, 2);

        Delivery.QuantitySync sync = d.syncShippableQuantities(Map.of(101L, 1, 102L, 0));
        assertThat(sync.reducedUnits()).isEqualTo(4);
        assertThat(tee.getQuantity()).isEqualTo(1);
        assertThat(s.getItems()).extracting(ShipmentItem::getOrderProductId).containsExactly(101L);
        assertThat(s.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(sync.dispatchedExcess()).isEmpty();

        assertThat(d.syncShippableQuantities(Map.of(101L, 1, 102L, 0)).changed()).as("멱등").isFalse();
        assertThat(d.syncShippableQuantities(Map.of(101L, 5)).changed()).as("늘리지 않는다").isFalse();
        assertThat(tee.getQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 분할 배송 — 나중 상자부터 줄이고, 빈 상자는 취소, 송장이 붙은 상자는 건드리지 않고 초과분을 돌려준다")
    void splitShipmentsLatestFirstAndDispatchedUntouched() {
        Delivery d = delivery();
        Shipment first = d.addShipment(false);
        Shipment second = d.addShipment(false);
        ShipmentItem shipped = item(first, 101, 2);
        item(second, 101, 1);
        first.assignTracking("KOREA_POST", "1111", true);

        Delivery.QuantitySync sync = d.syncShippableQuantities(Map.of(101L, 0));
        assertThat(sync.reducedUnits()).as("고칠 수 있는 상자의 1개만").isEqualTo(1);
        assertThat(second.getStatus()).as("빈 상자는 취소").isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(sync.cancelledShipments()).containsExactly(2);
        assertThat(shipped.getQuantity()).as("송장이 붙은 상자는 그대로").isEqualTo(2);
        assertThat(sync.dispatchedExcess()).as("사람이 볼 초과분").containsEntry(101L, 2);
    }

    @Test
    @DisplayName("목록에 없는 품목은 그대로(옛 주문 · 다른 경로로 더한 줄)")
    void unknownLinesAreLeftAlone() {
        Delivery d = delivery();
        Shipment s = d.addShipment(false);
        ShipmentItem other = item(s, 201, 2);
        assertThat(d.syncShippableQuantities(Map.of(101L, 0)).changed()).isFalse();
        assertThat(other.getQuantity()).isEqualTo(2);
    }
}
