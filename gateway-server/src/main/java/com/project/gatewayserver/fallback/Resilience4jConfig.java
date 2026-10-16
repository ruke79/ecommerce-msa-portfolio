package com.project.gatewayserver.fallback;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서킷 브레이커의 상태를 로그로 — 실패율 초과(ERROR) · 상태 전이(WARN). 개별 호출 실패는 DEBUG. */
@Configuration
@Slf4j
public class Resilience4jConfig {
    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventConsumer() {
        return new RegistryEventConsumer<CircuitBreaker>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
                CircuitBreaker circuitBreaker = entryAddedEvent.getAddedEntry();
                circuitBreaker.getEventPublisher()
                        .onFailureRateExceeded(event -> log.error("Circuit breaker '{}' failure rate {}% exceeded at {}",
                                event.getCircuitBreakerName(), event.getFailureRate(), event.getCreationTime()))
                        // 호출 실패 하나하나는 DEBUG — 백엔드 한 대가 흔들리면 요청마다 ERROR 가 쏟아졌다. 알릴 것은 실패율 초과 ·
                        // 상태 전이뿐이고(위 · 아래), 실패 수는 resilience4j 지표에 있다.
                        // [JA] 個々の呼び出し失敗は DEBUG — 知らせるのは失敗率超過・状態遷移だけ。失敗数はメトリクスにある。
                        .onError(event -> log.debug("Circuit breaker '{}' call failed after {}ms: {}",
                                event.getCircuitBreakerName(), event.getElapsedDuration().toMillis(),
                                event.getThrowable().getClass().getSimpleName()))
                        .onStateTransition(event -> log.warn("Circuit breaker '{}' state transition from '{}' to '{}' at {}",
                                event.getCircuitBreakerName(), event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState(), event.getCreationTime()));
            }
            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
                log.debug("Circuit breaker '{}' removed", entryRemoveEvent.getRemovedEntry().getName());
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
                log.debug("Circuit breaker '{}' replaced", entryReplacedEvent.getNewEntry().getName());
            }
        };
    }
}
