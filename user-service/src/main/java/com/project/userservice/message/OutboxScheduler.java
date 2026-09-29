package com.project.userservice.message;

public interface OutboxScheduler {
    void processOutboxMessage();
}
