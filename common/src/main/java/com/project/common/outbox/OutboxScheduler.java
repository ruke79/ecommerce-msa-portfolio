package com.project.common.outbox;

public interface OutboxScheduler {
    void processOutboxMessage();
}
