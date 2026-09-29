package com.project.common.domain.event.publisher;

import com.project.common.domain.event.DomainEvent;

public interface DomainEventPublisher<T extends DomainEvent> {

    void publish(T domainEvent);
}
