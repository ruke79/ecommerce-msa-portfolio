package com.project.delivery_service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.delivery_service.carrier.CarrierAdapter;
import com.project.delivery_service.carrier.KoreaPostCarrierAdapter;
import com.project.delivery_service.carrier.SimulatedCarrierAdapter;

import lombok.extern.slf4j.Slf4j;

/**
 * 택배사 어댑터 등록.
 *
 * <p>새 택배사를 추가할 때 여기에 빈을 하나 더하면 {@code CarrierRegistry} 가 자동으로
 * 인식한다 — 스케줄러·상태 판정·화면은 손대지 않는다(docs/25 §4.2).
 */
@Slf4j
@Configuration
public class CarrierConfig {

    @Value("${delivery.carriers.koreapost.base-url:https://apis.data.go.kr/1721000/TrackingInfoService/getTrackingInfo}")
    private String koreaPostBaseUrl;

    @Value("${delivery.carriers.koreapost.service-key:}")
    private String koreaPostServiceKey;

    @Value("${delivery.carriers.koreapost.field.events-path:response.body.items.item}")
    private String eventsPath;

    @Value("${delivery.carriers.koreapost.field.time:dlvyDate}")
    private String timeField;

    @Value("${delivery.carriers.koreapost.field.status:dlvySttus}")
    private String statusField;

    @Value("${delivery.carriers.koreapost.field.location:nowLc}")
    private String locationField;

    @Value("${delivery.carriers.koreapost.field.description:dlvyNm}")
    private String descriptionField;

    @Value("${delivery.carriers.simulated.enabled:true}")
    private boolean simulatedEnabled;

    @Value("${delivery.carriers.simulated.step-duration-ms:21600000}")
    private long simulatedStepMs;

    /**
     * 우체국.
     *
     * <p>서비스키가 없어도 빈은 만든다 — 없으면 호출 시 "키가 설정되지 않았다"는 영구 실패를
     * 돌려준다. 빈을 아예 안 만들면 그 택배사로 등록된 배송이 <b>이유 없이 갱신되지 않는</b>
     * 상태가 되고, 그건 진단이 훨씬 어렵다.
     */
    @Bean
    public CarrierAdapter koreaPostCarrierAdapter(ObjectMapper objectMapper) {
        if (koreaPostServiceKey == null || koreaPostServiceKey.isBlank()) {
            log.warn("우체국 서비스키가 없습니다 — 실제 조회는 실패합니다 "
                    + "(DELIVERY_KOREAPOST_SERVICE_KEY). 개발에는 SIMULATED 택배사를 쓰세요.");
        }
        return new KoreaPostCarrierAdapter(
                koreaPostBaseUrl, koreaPostServiceKey,
                new KoreaPostCarrierAdapter.FieldMapping(
                        eventsPath, timeField, statusField, locationField, descriptionField),
                Duration.ofSeconds(5), objectMapper);
    }

    /**
     * 시뮬레이터. 실제 택배사 없이 결제→배송→추적→알림 전 경로를 돌려 보기 위한 것이다.
     * 운영에서는 끈다.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "delivery.carriers.simulated.enabled", havingValue = "true", matchIfMissing = true)
    public CarrierAdapter simulatedCarrierAdapter() {
        return new SimulatedCarrierAdapter(Duration.ofMillis(simulatedStepMs), null);
    }
}
