package com.project.delivery_service.carrier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.project.delivery_service.domain.DeliveryStatus;

/**
 * 테스트배송(시뮬레이터)이 이력에 남기는 문구 (docs/60 §10).
 *
 * <p><b>저장은 한국어 그대로</b>다. 장소는 이력 중복 제거 키({@code DeliveryEvent.dedupKey})의 일부라, 언어에 따라 다르게
 * 저장하면 같은 이벤트가 두 번 쌓인다. 대신 조회 응답을 만들 때({@code DeliveryResponse}) 화면 언어로 바꾼다 — 그 번역이
 * {@code i18n/client-text.ja.txt} 에 있고, 빠진 것은 {@code SimulatedCarrierTextsTest} 가 잡는다.
 *
 * <p>실제 택배사(우체국)의 문구는 택배사가 보낸 <b>데이터</b>라 여기에 없다 — 카탈로그에 없으면 원문 그대로 나간다.
 */
public final class SimulatedCarrierTexts {

    /** 단계마다 지나는 곳. 마지막 곳에서 멈춘다. */
    static final List<String> PLACES = List.of(
            "서울강남", "서울물류센터", "대전허브", "인천세관", "부산물류센터", "수원", "수원");

    static final Map<DeliveryStatus, String> DESCRIPTIONS;

    static {
        Map<DeliveryStatus, String> m = new EnumMap<>(DeliveryStatus.class);
        m.put(DeliveryStatus.INFO_RECEIVED, "접수되었습니다");
        m.put(DeliveryStatus.PICKED_UP, "집화완료");
        m.put(DeliveryStatus.IN_TRANSIT, "이동 중입니다");
        m.put(DeliveryStatus.CUSTOMS_HOLD, "통관 진행 중입니다");
        m.put(DeliveryStatus.CUSTOMS_CLEARED, "통관이 완료되었습니다");
        m.put(DeliveryStatus.OUT_FOR_DELIVERY, "배달을 시작했습니다");
        m.put(DeliveryStatus.ATTEMPT_FAILED, "수취인 부재로 배달하지 못했습니다");
        m.put(DeliveryStatus.DELIVERED, "배달이 완료되었습니다");
        m.put(DeliveryStatus.EXCEPTION, "배송 중 문제가 발생했습니다");
        m.put(DeliveryStatus.RETURNED, "반송되었습니다");
        DESCRIPTIONS = Collections.unmodifiableMap(m);
    }

    /** 이 서비스가 만드는 택배사 이름(V3 시드의 name_ko). 화면의 "택배사" 칸에 그대로 나간다. */
    static final List<String> CARRIER_NAMES = List.of("우체국택배", "테스트배송", "CJ대한통운", "한진택배", "롯데택배");

    /** 번역이 있어야 하는 문구 전부 — 검사용. */
    public static final List<String> FORMATS;

    static {
        List<String> all = new ArrayList<>(PLACES);
        all.addAll(DESCRIPTIONS.values());
        all.addAll(CARRIER_NAMES);
        FORMATS = List.copyOf(all);
    }

    private SimulatedCarrierTexts() {
    }

    static String locationFor(int step) {
        return PLACES.get(Math.min(step, PLACES.size() - 1));
    }

    static String descriptionFor(DeliveryStatus status) {
        return DESCRIPTIONS.getOrDefault(status, status.name());
    }
}
