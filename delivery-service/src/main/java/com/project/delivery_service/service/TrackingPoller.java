package com.project.delivery_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.delivery_service.carrier.CarrierAdapter;
import com.project.delivery_service.carrier.CarrierRegistry;
import com.project.delivery_service.carrier.TrackingEvent;
import com.project.delivery_service.carrier.TrackingResult;
import com.project.delivery_service.domain.DeliveryEvent;
import com.project.delivery_service.domain.DeliveryStatus;
import com.project.delivery_service.domain.Shipment;
import com.project.delivery_service.publisher.DeliveryStatusPublisher;
import com.project.delivery_service.repository.DeliveryEventRepository;
import com.project.delivery_service.repository.ShipmentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 택배사에서 배송 진행 상황을 가져온다.
 *
 * <p>한국 택배사는 대부분 웹훅을 주지 않으므로 <b>폴링이 현실</b>이다. 그래서 이 클래스가
 * 신경 쓰는 것은 대부분 "어떻게 하면 적게 호출하면서 놓치지 않을까"이다.
 *
 * <ul>
 *   <li><b>선점</b>({@code FOR UPDATE SKIP LOCKED}) — 레플리카 둘이 같은 상자를 조회하면
 *       택배사 쿼터를 두 배로 태운다(docs/21 과 같은 구조).</li>
 *   <li><b>상태별 주기</b> — 배달 출발이면 30분, 이동 중이면 3시간, 끝났으면 중단.
 *       전 건을 같은 주기로 돌면 공공 API 일일 한도를 금방 넘긴다.</li>
 *   <li><b>중복 제거</b> — 폴링은 매번 전체 이력을 돌려준다. 그대로 저장하면 타임라인이
 *       조회 횟수만큼 중복된다.</li>
 *   <li><b>상태가 바뀔 때만 발행</b> — 폴링마다 발행하면 3시간마다 알림이 간다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingPoller {

    private final ShipmentRepository shipmentRepository;
    private final DeliveryEventRepository eventRepository;
    private final CarrierRegistry carriers;
    private final DeliveryStatusPublisher publisher;

    @Value("${delivery.polling.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${delivery.polling.interval-ms:60000}",
               initialDelayString = "${delivery.polling.initial-delay-ms:15000}")
    @Transactional
    public void poll() {
        List<Shipment> due = shipmentRepository.claimDueForPolling(
                LocalDateTime.now(ZoneOffset.UTC), PageRequest.of(0, batchSize));

        if (due.isEmpty()) {
            return;
        }
        log.debug("추적 조회 대상 {}건 선점", due.size());

        for (Shipment shipment : due) {
            try {
                pollOne(shipment);
            } catch (RuntimeException e) {
                // 한 건의 실패가 배치 전체를 멈추면 안 된다. 그 건만 백오프시키고 넘어간다.
                log.warn("추적 조회 중 예외: shipmentId={} {}", shipment.getShipmentId(), e.getMessage());
                shipment.recordSyncFailure();
            }
        }
    }

    private void pollOne(Shipment shipment) {
        CarrierAdapter adapter = carriers.find(shipment.getCarrierCode()).orElse(null);
        if (adapter == null) {
            // 어댑터가 없는 택배사다. 계속 조회 대상으로 두면 매 주기마다 로그만 쌓인다.
            log.info("어댑터가 없는 택배사라 폴링을 중단한다: {}", shipment.getCarrierCode());
            shipment.recordSyncFailure();
            return;
        }

        TrackingResult result = adapter.track(shipment.getTrackingNumber(), shipment.isInternational());

        switch (result.status()) {
            case RETRYABLE -> {
                shipment.recordSyncFailure();
                log.debug("일시적 실패, 백오프: shipmentId={} {}",
                        shipment.getShipmentId(), result.detail());
                return;
            }
            case PERMANENT -> {
                // 인증 실패나 응답 형식 오류다. 재시도해도 같으므로 길게 물러서고 사람이 보게 남긴다.
                log.error("추적 영구 실패: shipmentId={} 택배사={} {}",
                        shipment.getShipmentId(), shipment.getCarrierCode(), result.detail());
                shipment.recordSyncFailure();
                return;
            }
            case NOT_FOUND -> {
                // 등록 직후에는 정상이다 — 택배사 시스템에 아직 반영되지 않았다.
                // 며칠째 계속이면 송장번호가 잘못됐다는 뜻이고, 실패 카운터가 그걸 드러낸다.
                log.debug("택배사가 송장을 모름: {} (등록 직후면 정상)", shipment.getTrackingNumber());
                shipment.recordSyncFailure();
                return;
            }
            default -> { /* OK — 아래에서 처리 */ }
        }

        applyEvents(shipment, result);
        // 택배사가 자기 갱신 주기를 알면 그것을 따른다(CarrierAdapter.pollIntervalCap 주석).
        shipment.recordSyncSuccess(adapter.pollIntervalCap().orElse(null));
    }

    private void applyEvents(Shipment shipment, TrackingResult result) {
        Set<String> known = eventRepository.findDedupKeys(shipment.getShipmentId());

        List<TrackingEvent> fresh = result.events().stream()
                .filter(e -> !known.contains(
                        DeliveryEvent.dedupKey(e.occurredAt(), e.rawStatus(), e.location())))
                .sorted(Comparator.comparing(TrackingEvent::occurredAt))
                .toList();

        for (TrackingEvent event : fresh) {
            shipment.addEvent(new DeliveryEvent(shipment, event.status(), event.occurredAt(),
                    event.location(), event.description(), event.rawStatus(), event.rawPayload()));
        }

        if (result.estimatedAt() != null) {
            shipment.setEstimatedAt(result.estimatedAt());
        }

        // 상태는 **가장 최근 이벤트**를 따른다. 이력 전체를 순서대로 적용하면 중간 상태마다
        // 전이가 일어나 알림이 여러 번 나간다.
        DeliveryStatus latest = result.events().stream()
                .max(Comparator.comparing(TrackingEvent::occurredAt))
                .map(TrackingEvent::status)
                .orElse(null);

        LocalDateTime occurredAt = result.events().stream()
                .max(Comparator.comparing(TrackingEvent::occurredAt))
                .map(TrackingEvent::occurredAt)
                .orElse(null);

        boolean changed = shipment.advanceTo(latest, occurredAt);

        if (changed) {
            log.info("배송 상태 전이: shipmentId={} → {} (새 이벤트 {}건)",
                    shipment.getShipmentId(), shipment.getStatus(), fresh.size());
            // 상태가 바뀐 경우에만 발행한다. 폴링마다 발행하면 알림이 주기마다 나간다.
            publisher.publish(shipment.getDelivery(), shipment.getStatus());
        }
    }
}
