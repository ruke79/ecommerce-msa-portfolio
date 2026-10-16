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
import org.springframework.transaction.support.TransactionTemplate;

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

    /** 선점한 상자를 조회하는 동안의 리스 — 이 안에 조회 · 반영을 끝내지 못하면 다른 파드가 다시 가져간다. */
    @Value("${delivery.polling.lease-minutes:10}")
    private long leaseMinutes = 10;

    /** 없으면(단위 테스트) 그냥 부른다 — 리포지토리 호출마다 자기 트랜잭션이다. */
    private TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 선점한 상자 — 택배사 호출에 필요한 것만 트랜잭션 밖으로 들고 나간다. */
    private record Claim(Shipment shipment, Long shipmentId, String carrierCode, String trackingNumber, boolean international) {}

    /**
     * 한 주기.
     *
     * <p>🐛 예전에는 메서드 전체가 트랜잭션이었다 — 상자 20개를 {@code FOR UPDATE} 로 잡은 채 택배사 HTTP(접속 5초 + 응답 10초)를
     * 차례로 불러, 최악이면 5분 동안 커넥션(풀 5개)과 행 잠금을 쥐었다. 그 사이 고객 취소 · 주소 변경 · 송장 등록이 막혔다가
     * {@code @Version} 충돌로 500 이 됐다. 또 한 건의 DB 오류가 바깥 트랜잭션을 rollback-only 로 만들어 catch 로 삼켜도 배치 20건이
     * 모두 되돌아갔다 — 실패 기록 · 다음 조회 시각도 함께 — 같은 배치가 매분 다시 선점돼 전체 폴링이 멈췄다.
     *
     * <p>이제 ① 짧은 트랜잭션으로 선점하고 리스(다음 조회 시각을 잠시 미룸)를 건 뒤 커밋, ② 택배사 호출은 트랜잭션 밖, ③ 결과는 상자마다
     * 자기 트랜잭션으로 다시 읽어 반영한다. 반영이 실패하면 그 상자만 따로 실패를 기록한다.
     *
     * <p>[JA] 一周期。以前はメソッド全体がトランザクションで、20 箱を FOR UPDATE で掴んだまま宅配会社 HTTP を順に呼び、最悪 5 分間
     * 接続と行ロックを保持していた。一件の DB エラーでバッチ全体が戻っていた。今は ① 短いトランザクションで先取りしリースを掛けて
     * コミット、② 宅配会社の呼び出しはトランザクションの外、③ 結果は箱ごとに自分のトランザクションで読み直して反映する。
     */
    @Scheduled(fixedDelayString = "${delivery.polling.interval-ms:60000}",
               initialDelayString = "${delivery.polling.initial-delay-ms:15000}")
    public void poll() {
        List<Claim> claims = inTx(this::claim);
        if (claims.isEmpty()) {
            return;
        }
        log.debug("추적 조회 대상 {}건 선점", claims.size());

        for (Claim claim : claims) {
            CarrierAdapter adapter = carriers.find(claim.carrierCode()).orElse(null);
            TrackingResult result = null;
            if (adapter != null) {
                try {
                    result = adapter.track(claim.trackingNumber(), claim.international());
                } catch (RuntimeException e) {
                    log.warn("택배사 조회 중 예외: shipmentId={} {}", claim.shipmentId(), e.toString());
                }
            }
            TrackingResult fetched = result;
            try {
                inTx(() -> {
                    apply(claim, adapter, fetched);
                    return null;
                });
            } catch (RuntimeException e) {
                // 한 건의 실패가 배치 전체를 멈추면 안 된다. 그 건만 따로 백오프시키고 넘어간다.
                log.warn("추적 결과 반영 중 예외: shipmentId={} {}", claim.shipmentId(), e.toString());
                try {
                    inTx(() -> {
                        reload(claim).ifPresent(Shipment::recordSyncFailure);
                        return null;
                    });
                } catch (RuntimeException again) {
                    log.warn("실패 기록도 하지 못했다 — 리스가 끝나면 다시 조회한다: shipmentId={} {}",
                            claim.shipmentId(), again.toString());
                }
            }
        }
    }

    private List<Claim> claim() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Shipment> due = shipmentRepository.claimDueForPolling(now, PageRequest.of(0, batchSize));
        LocalDateTime lease = now.plusMinutes(leaseMinutes);
        return due.stream().map(s -> {
            s.leaseUntil(lease);
            return new Claim(s, s.getShipmentId(), s.getCarrierCode(), s.getTrackingNumber(), s.isInternational());
        }).toList();
    }

    /** 반영할 때 다시 읽는다 — 조회하는 동안 취소 · 송장 변경이 있었을 수 있다. id 가 없으면(단위 테스트) 선점한 그 객체. */
    private java.util.Optional<Shipment> reload(Claim claim) {
        return claim.shipmentId() == null ? java.util.Optional.of(claim.shipment())
                : shipmentRepository.findById(claim.shipmentId());
    }

    private void apply(Claim claim, CarrierAdapter adapter, TrackingResult result) {
        Shipment shipment = reload(claim).orElse(null);
        if (shipment == null) {
            return;
        }
        if (adapter == null) {
            // 어댑터가 없는 택배사다. 계속 조회 대상으로 두면 매 주기마다 로그만 쌓인다.
            log.info("어댑터가 없는 택배사라 폴링을 미룬다: {}", claim.carrierCode());
            shipment.recordSyncFailure();
            return;
        }
        if (result == null) {
            shipment.recordSyncFailure();
            return;
        }

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

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return tx == null ? work.get() : tx.execute(status -> work.get());
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

        DeliveryStatus overallBefore = shipment.getDelivery().overallStatus();
        boolean changed = shipment.advanceTo(latest, occurredAt);

        if (changed) {
            log.info("배송 상태 전이: shipmentId={} → {} (새 이벤트 {}건)",
                    shipment.getShipmentId(), shipment.getStatus(), fresh.size());
            // 상태가 바뀐 경우에만, 그리고 주문 전체 상태가 바뀐 경우에만 발행한다(분할 배송 — 상자 하나의 도착은 주문의 도착이 아니다).
            // 폴링마다 발행하면 알림이 주기마다 나간다.
            publisher.publishIfOverallChanged(shipment.getDelivery(), overallBefore);
        }
    }
}
