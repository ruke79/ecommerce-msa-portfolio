package com.project.delivery_service.carrier;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.project.delivery_service.domain.DeliveryStatus;

/**
 * 실제 택배사 없이 배송 흐름 전체를 돌려 보기 위한 어댑터.
 *
 * <p><b>왜 필요한가.</b> 우체국 API 는 서비스키가 있어야 하고, 키가 있어도 실제 송장번호가
 * 있어야 한다. 그 둘이 없으면 결제→배송생성→폴링→상태전이→알림→주문마감 이라는 <b>전 경로를
 * 한 번도 실행해 보지 못한 채</b> 코드를 커밋하게 된다. 이 저장소에서 가장 비쌌던 버그들이
 * 하나같이 "실행해야만 보이는" 종류였다는 점을 생각하면 그건 받아들일 수 없다.
 *
 * <p>송장번호를 만든 시각으로부터 <b>경과 시간에 따라</b> 이벤트를 만들어 낸다. 시간이 흐르면
 * 상태가 실제로 진행하므로 폴링·중복제거·상태전이·알림이 모두 진짜처럼 동작한다.
 *
 * <p>테스트에서는 시계를 주입해 몇 시간을 순식간에 지나가게 할 수 있다.
 *
 * <p><b>송장번호로 시나리오를 고른다</b> — 해피패스만 검증하면 실제 운영에서 바로 막힌다:
 * <ul>
 *   <li>{@code SIM-...}      정상 진행 (집화 → 이동 → 배달출발 → 완료)</li>
 *   <li>{@code SIMFAIL-...}  배달 시도 실패 후 완료</li>
 *   <li>{@code SIMEXC-...}   분실(예외)에서 멈춤</li>
 *   <li>{@code SIMINTL-...}  국제 배송 — 통관 보류를 거친다</li>
 *   <li>{@code SIMNONE-...}  택배사가 송장을 모름 (등록 직후 또는 오타)</li>
 * </ul>
 */
public class SimulatedCarrierAdapter implements CarrierAdapter {

    public static final String CODE = "SIMULATED";

    /** 한 단계를 진행하는 데 걸리는 시간. 테스트는 짧게 준다. */
    private final java.time.Duration stepDuration;
    private final java.util.function.Supplier<LocalDateTime> clock;

    /** 이 송장번호가 처음 조회된 시각. 경과 시간의 기준이 된다. */
    private final java.util.Map<String, LocalDateTime> firstSeen = new java.util.concurrent.ConcurrentHashMap<>();

    public SimulatedCarrierAdapter(java.time.Duration stepDuration,
                                   java.util.function.Supplier<LocalDateTime> clock) {
        this.stepDuration = stepDuration;
        this.clock = clock == null ? () -> LocalDateTime.now(ZoneOffset.UTC) : clock;
    }

    public SimulatedCarrierAdapter() {
        this(java.time.Duration.ofHours(6), null);
    }

    @Override
    public String carrierCode() {
        return CODE;
    }

    /** 한 단계가 지날 때마다 이력이 하나 는다. 그보다 자주 물을 이유도, 드물게 물을 이유도 없다. */
    @Override
    public java.util.Optional<java.time.Duration> pollIntervalCap() {
        return java.util.Optional.of(stepDuration);
    }

    @Override
    public TrackingResult track(String trackingNumber, boolean international) {
        if (trackingNumber == null || trackingNumber.startsWith("SIMNONE")) {
            return TrackingResult.notFound();
        }

        LocalDateTime start = firstSeen.computeIfAbsent(trackingNumber, k -> clock.get());
        long steps = java.time.Duration.between(start, clock.get()).toMillis()
                / Math.max(1, stepDuration.toMillis());

        List<DeliveryStatus> script = scriptFor(trackingNumber, international);
        List<TrackingEvent> events = new ArrayList<>();

        // 경과한 단계까지의 이벤트를 만든다. 매 조회마다 **전체 이력**을 돌려주는 것이
        // 실제 택배사 API 의 동작이고, 그래서 중복 제거가 필요하다.
        for (int i = 0; i <= Math.min(steps, script.size() - 1); i++) {
            DeliveryStatus status = script.get(i);
            LocalDateTime occurredAt = start.plus(stepDuration.multipliedBy(i));
            events.add(new TrackingEvent(
                    status,
                    occurredAt,
                    locationFor(i),
                    descriptionFor(status),
                    "SIM:" + status.name(),
                    "{\"step\":" + i + ",\"status\":\"" + status.name() + "\"}"));
        }

        if (events.isEmpty()) {
            return TrackingResult.notFound();
        }
        LocalDateTime estimated = start.plus(stepDuration.multipliedBy(script.size() - 1));
        return TrackingResult.ok(events, estimated);
    }

    private List<DeliveryStatus> scriptFor(String trackingNumber, boolean international) {
        if (trackingNumber.startsWith("SIMEXC")) {
            return List.of(DeliveryStatus.INFO_RECEIVED, DeliveryStatus.PICKED_UP,
                    DeliveryStatus.IN_TRANSIT, DeliveryStatus.EXCEPTION);
        }
        if (trackingNumber.startsWith("SIMFAIL")) {
            return List.of(DeliveryStatus.INFO_RECEIVED, DeliveryStatus.PICKED_UP,
                    DeliveryStatus.IN_TRANSIT, DeliveryStatus.OUT_FOR_DELIVERY,
                    DeliveryStatus.ATTEMPT_FAILED, DeliveryStatus.OUT_FOR_DELIVERY,
                    DeliveryStatus.DELIVERED);
        }
        if (trackingNumber.startsWith("SIMINTL") || international) {
            return List.of(DeliveryStatus.INFO_RECEIVED, DeliveryStatus.PICKED_UP,
                    DeliveryStatus.IN_TRANSIT, DeliveryStatus.CUSTOMS_HOLD,
                    DeliveryStatus.CUSTOMS_CLEARED, DeliveryStatus.OUT_FOR_DELIVERY,
                    DeliveryStatus.DELIVERED);
        }
        return List.of(DeliveryStatus.INFO_RECEIVED, DeliveryStatus.PICKED_UP,
                DeliveryStatus.IN_TRANSIT, DeliveryStatus.OUT_FOR_DELIVERY,
                DeliveryStatus.DELIVERED);
    }

    // 문구는 SimulatedCarrierTexts 에 모아 둔다 — 조회 응답이 화면 언어로 바꾼다(docs/60 §10).
    private static String locationFor(int step) {
        return SimulatedCarrierTexts.locationFor(step);
    }

    private static String descriptionFor(DeliveryStatus status) {
        return SimulatedCarrierTexts.descriptionFor(status);
    }
}
