package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.delivery_service.carrier.CarrierRegistry;
import com.project.delivery_service.carrier.SimulatedCarrierAdapter;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.DeliveryEventRepository;
import com.project.delivery_service.repository.ShipmentRepository;

/**
 * 분할 배송 — 주문 단위 이벤트는 주문 전체 상태로만 나간다.
 *
 * <p>🐛 예전: 상자 하나의 상태를 그대로 주문 이벤트로 냈다. 상자 1 이 도착하면 {@code DELIVERED} 가 나가 주문이 DELIVERED 가 되고
 * 자동 구매 확정까지 갔다 — 상자 2 는 아직 이동 중인데.
 *
 * <p>[JA] 分割配送 — 注文単位のイベントは注文全体の状態でだけ出る。以前は箱 1 が届くと DELIVERED が出て、箱 2 が移動中でも
 * 注文が DELIVERED になり自動購入確定まで進んだ。
 */
@DisplayName("분할 배송 — 주문 전체 상태로만 발행")
class SplitDeliveryPublishTest {

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>(LocalDateTime.now(ZoneOffset.UTC));

    private DeliveryStatusPublisher publisher;
    private TrackingPoller poller;
    private Delivery delivery;
    private Shipment first;
    private Shipment second;

    @BeforeEach
    void setUp() {
        ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
        DeliveryEventRepository eventRepository = mock(DeliveryEventRepository.class);
        publisher = mock(DeliveryStatusPublisher.class);
        when(publisher.publishIfOverallChanged(any(), any())).thenCallRealMethod();
        CarrierRegistry carriers = mock(CarrierRegistry.class);
        when(carriers.find("SIMULATED")).thenReturn(Optional.of(new SimulatedCarrierAdapter(Duration.ofMinutes(1), now::get)));
        when(eventRepository.findDedupKeys(anyLong())).thenReturn(Set.of());

        poller = new TrackingPoller(shipmentRepository, eventRepository, carriers, publisher);
        ReflectionTestUtils.setField(poller, "batchSize", 20);

        delivery = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        first = delivery.addShipment(false);
        first.assignTracking("SIMULATED", "SIM-1", true);
        second = delivery.addShipment(false);
        second.assignTracking("KOREA_POST", "1234567890123", true);
        second.advanceTo(DeliveryStatus.IN_TRANSIT, now.get());
        when(shipmentRepository.claimDueForPolling(any(), any())).thenReturn(List.of(first));
    }

    @Test
    @DisplayName("🔴 상자 1 이 도착해도 상자 2 가 이동 중이면 DELIVERED · 배달 출발을 내지 않는다 — 둘 다 오면 그때 DELIVERED")
    void firstBoxArrivingIsNotTheOrderArriving() {
        for (int step = 0; step < 5; step++) {
            long advance = step == 0 ? 0 : 1;
            now.updateAndGet(t -> t.plusMinutes(advance));
            poller.poll();
        }
        assertThat(first.getStatus()).as("전제 — 상자 1 은 도착했다").isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.overallStatus()).isEqualTo(DeliveryStatus.IN_TRANSIT);
        verify(publisher, never()).publish(any(), eq(DeliveryStatus.DELIVERED));
        verify(publisher, never()).publish(any(), eq(DeliveryStatus.OUT_FOR_DELIVERY));

        DeliveryStatus before = delivery.overallStatus();
        second.advanceTo(DeliveryStatus.DELIVERED, now.get());
        assertThat(publisher.publishIfOverallChanged(delivery, before)).isTrue();
        verify(publisher).publish(delivery, DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("주문 전체 상태가 그대로면 발행하지 않는다")
    void unchangedOverallIsNotPublished() {
        DeliveryStatus before = delivery.overallStatus();
        assertThat(publisher.publishIfOverallChanged(delivery, before)).isFalse();
        verify(publisher, never()).publish(any(), any());
    }
}
