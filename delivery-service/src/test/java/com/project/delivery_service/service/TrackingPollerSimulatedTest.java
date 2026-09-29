package com.project.delivery_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
 * 폴러 + 시뮬레이터 — 배송 완료까지 <b>단계마다</b> 간다.
 *
 * <p>🐛 예전에는 첫 조회 뒤 다음 조회가 6시간 뒤였다(접수 상태의 기본 주기). 시뮬레이터가
 * 1분마다 단계를 밟아도 폴러는 6시간 뒤에야 한 번 더 물었고, 그때 이력 전체를 받아 곧장
 * 배달 완료로 뛰었다. 화면에서는 "접수" 다음이 바로 "배달 완료" 였고 그 사이의 알림은 없었다.
 */
@DisplayName("delivery-service — 폴러가 시뮬레이터의 리듬을 따른다")
class TrackingPollerSimulatedTest {

    private final AtomicReference<LocalDateTime> now =
            new AtomicReference<>(LocalDateTime.now(ZoneOffset.UTC));
    private final SimulatedCarrierAdapter simulated =
            new SimulatedCarrierAdapter(Duration.ofMinutes(1), now::get);

    private ShipmentRepository shipmentRepository;
    private DeliveryStatusPublisher publisher;
    private TrackingPoller poller;
    private Shipment shipment;

    @BeforeEach
    void setUp() {
        shipmentRepository = mock(ShipmentRepository.class);
        DeliveryEventRepository eventRepository = mock(DeliveryEventRepository.class);
        publisher = mock(DeliveryStatusPublisher.class);
        CarrierRegistry carriers = mock(CarrierRegistry.class);
        when(carriers.find("SIMULATED")).thenReturn(Optional.of(simulated));
        when(eventRepository.findDedupKeys(anyLong())).thenReturn(Set.of());

        poller = new TrackingPoller(shipmentRepository, eventRepository, carriers, publisher);
        ReflectionTestUtils.setField(poller, "batchSize", 20);

        Delivery d = Delivery.builder().orderId(1L).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        shipment = d.addShipment(false);
        shipment.assignTracking("SIMULATED", "SIM-1", true);
        when(shipmentRepository.claimDueForPolling(any(), any())).thenReturn(List.of(shipment));
    }

    private Duration untilNextPoll() {
        return Duration.between(LocalDateTime.now(ZoneOffset.UTC), shipment.getNextPollAt());
    }

    /** 🔴 핵심 — 첫 조회 뒤 다음 조회가 6시간이 아니라 시뮬레이터의 한 단계(1분) 뒤다. */
    @Test
    @DisplayName("🔴 접수 뒤 다음 조회를 6시간이 아니라 시뮬레이터 단계 간격으로 잡는다")
    void nextPollFollowsTheSimulatorStep() {
        poller.poll();

        assertThat(shipment.getStatus()).isEqualTo(DeliveryStatus.INFO_RECEIVED);
        assertThat(untilNextPoll().toSeconds()).isBetween(55L, 65L);
    }

    /** 단계마다 한 번씩 조회하면 전이도 단계마다 일어나고 알림도 그때마다 나간다. */
    @Test
    @DisplayName("단계마다 물으면 집화 → 이동 → 배달 출발 → 배달 완료를 하나씩 밟는다")
    void walksEveryStageToDelivered() {
        DeliveryStatus[] expected = {
            DeliveryStatus.INFO_RECEIVED, DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT,
            DeliveryStatus.OUT_FOR_DELIVERY, DeliveryStatus.DELIVERED,
        };
        for (int step = 0; step < expected.length; step++) {
            long advance = step == 0 ? 0 : 1;
            now.updateAndGet(t -> t.plusMinutes(advance));
            poller.poll();
            assertThat(shipment.getStatus()).as("step " + step).isEqualTo(expected[step]);
        }

        assertThat(shipment.getNextPollAt()).as("끝나면 더 조회하지 않는다").isNull();
        for (DeliveryStatus s : expected) {
            if (s != DeliveryStatus.INFO_RECEIVED) {   // 접수는 송장 등록 때 이미 발행됐다
                verify(publisher).publish(any(), eq(s));
            }
        }
    }
}
