package com.project.gatewayserver.fallback;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
                        .onError(event -> log.error("Circuit breaker '{}' encountered error, duration: {}s",
                                event.getCircuitBreakerName(), event.getElapsedDuration().getSeconds()))
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
