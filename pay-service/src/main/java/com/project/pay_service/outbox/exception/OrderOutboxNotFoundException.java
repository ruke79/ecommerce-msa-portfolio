package com.project.pay_service.outbox.exception;

public class OrderOutboxNotFoundException extends RuntimeException {

    public OrderOutboxNotFoundException(String message) {
        super(message);
    }
}
