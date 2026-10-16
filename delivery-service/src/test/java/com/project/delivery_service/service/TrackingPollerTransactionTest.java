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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.project.delivery_service.carrier.CarrierAdapter;
import com.project.delivery_service.carrier.CarrierRegistry;
import com.project.delivery_service.carrier.SimulatedCarrierAdapter;
import com.project.delivery_service.carrier.TrackingResult;
import com.project.delivery_service.domain.Delivery;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.DeliveryEventRepository;
import com.project.delivery_service.repository.ShipmentRepository;

/**
 * 폴러의 트랜잭션 — 택배사 호출은 트랜잭션 밖, 결과는 상자마다 따로.
 *
 * <p>🐛 예전: 한 트랜잭션이 상자 20개를 잠근 채 택배사 HTTP 를 차례로 불렀고(최악 5분), 한 건의 DB 오류가 배치 전체를 되돌렸다.
 *
 * <p>[JA] ポーラーのトランザクション — 宅配会社の呼び出しはトランザクションの外、結果は箱ごとに別々。
 */
@DisplayName("추적 폴러 — 트랜잭션 경계")
class TrackingPollerTransactionTest {

    private final List<String> calls = new ArrayList<>();
    private ShipmentRepository shipmentRepository;
    private DeliveryEventRepository eventRepository;
    private DeliveryStatusPublisher publisher;
    private TrackingPoller poller;
    private Shipment first;
    private Shipment second;

    /** 트랜잭션 시작 · 끝을 기록하는 가짜 관리자. */
    private PlatformTransactionManager recordingManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                calls.add("begin");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                calls.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                calls.add("rollback");
            }
        };
    }

    @BeforeEach
    void setUp() {
        shipmentRepository = mock(ShipmentRepository.class);
        eventRepository = mock(DeliveryEventRepository.class);
        publisher = mock(DeliveryStatusPublisher.class);
        when(publisher.publishIfOverallChanged(any(), any())).thenCallRealMethod();

        SimulatedCarrierAdapter simulated = new SimulatedCarrierAdapter(Duration.ofMinutes(1),
                () -> LocalDateTime.now(ZoneOffset.UTC));
        CarrierAdapter recording = mock(CarrierAdapter.class);
        when(recording.track(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(i -> {
            calls.add("track");
            return simulated.track(i.getArgument(0), i.getArgument(1));
        });
        when(recording.pollIntervalCap()).thenReturn(Optional.empty());
        CarrierRegistry carriers = mock(CarrierRegistry.class);
        when(carriers.find("SIMULATED")).thenReturn(Optional.of(recording));

        poller = new TrackingPoller(shipmentRepository, eventRepository, carriers, publisher);
        ReflectionTestUtils.setField(poller, "batchSize", 20);
        poller.setTransactionManager(recordingManager());

        first = shipment(1L, 101L);
        second = shipment(2L, 102L);
        when(shipmentRepository.claimDueForPolling(any(), any())).thenReturn(List.of(first, second));
        when(shipmentRepository.findById(101L)).thenReturn(Optional.of(first));
        when(shipmentRepository.findById(102L)).thenReturn(Optional.of(second));
        when(eventRepository.findDedupKeys(anyLong())).thenReturn(Set.of());
    }

    private static Shipment shipment(long orderId, long shipmentId) {
        Delivery d = Delivery.builder().orderId(orderId).customerId(10L).recipientName("홍길동")
                .addressLine1("서울 강남구 테헤란로 1").country("KR").build();
        Shipment s = d.addShipment(false);
        s.assignTracking("SIMULATED", "SIM-" + orderId, true);
        ReflectionTestUtils.setField(s, "shipmentId", shipmentId);
        return s;
    }

    @Test
    @DisplayName("🔴 선점은 짧은 트랜잭션으로 끝나고, 택배사 호출은 트랜잭션 밖, 반영은 상자마다 따로")
    void carrierCallsAreOutsideTransactions() {
        poller.poll();

        assertThat(calls).containsExactly("begin", "commit", "track", "begin", "commit", "track", "begin", "commit");
        assertThat(first.getStatus()).isEqualTo(DeliveryStatus.INFO_RECEIVED);
    }

    @Test
    @DisplayName("🔴 한 상자의 반영이 실패해도 다른 상자는 반영된다 — 실패한 상자는 따로 실패를 기록한다")
    void oneFailureDoesNotRollBackTheBatch() {
        when(eventRepository.findDedupKeys(101L)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("too long"));
        when(eventRepository.findDedupKeys(102L)).thenReturn(Set.of());
        int failuresBefore = first.getPollFailures();

        poller.poll();

        assertThat(calls).containsSubsequence("track", "begin", "rollback", "begin", "commit", "track", "begin", "commit");
        assertThat(first.getPollFailures()).as("실패한 상자는 백오프").isEqualTo(failuresBefore + 1);
        assertThat(second.getPollFailures()).isZero();
        assertThat(second.getLastSyncedAt()).as("다른 상자는 반영됐다").isNotNull();
    }

    @Test
    @DisplayName("선점한 상자에는 리스가 걸린다 — 조회하는 동안 다른 파드가 다시 가져가지 않는다")
    void claimedShipmentsAreLeased() {
        when(shipmentRepository.findById(101L)).thenReturn(Optional.empty());
        when(shipmentRepository.findById(102L)).thenReturn(Optional.empty());

        poller.poll();

        assertThat(first.getNextPollAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(9));
    }

    @Test
    @DisplayName("poll 자체에는 트랜잭션이 없다")
    void pollIsNotTransactional() throws NoSuchMethodException {
        assertThat(TrackingPoller.class.getMethod("poll").getAnnotation(Transactional.class)).isNull();
    }
}
