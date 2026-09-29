package com.project.cart_service.outbox;

public interface OutboxScheduler {
    void processOutboxMessage();
}
