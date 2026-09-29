package com.project.delivery_service.carrier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 택배사 코드 → 어댑터.
 *
 * <p>Spring 이 주입해 주는 {@link CarrierAdapter} 빈을 전부 모아 코드로 색인한다.
 * 새 택배사를 붙일 때 이 클래스를 고칠 필요가 없다는 것이 핵심이다 — 어댑터를 빈으로
 * 등록하기만 하면 자동으로 여기 들어온다(docs/25 §4.2).
 */
@Slf4j
@Component
public class CarrierRegistry {

    private final Map<String, CarrierAdapter> adapters;

    public CarrierRegistry(List<CarrierAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(CarrierAdapter::carrierCode, Function.identity()));
        log.info("등록된 택배사 어댑터: {}", this.adapters.keySet());
    }

    /**
     * @return 어댑터. 없으면 비어 있음 — 추적을 지원하지 않는 택배사이거나 어댑터를
     *         아직 만들지 않은 경우다. 둘 다 정상 흐름이므로 예외를 던지지 않는다.
     */
    public Optional<CarrierAdapter> find(String carrierCode) {
        return Optional.ofNullable(adapters.get(carrierCode));
    }

    public boolean supports(String carrierCode) {
        return adapters.containsKey(carrierCode);
    }
}
