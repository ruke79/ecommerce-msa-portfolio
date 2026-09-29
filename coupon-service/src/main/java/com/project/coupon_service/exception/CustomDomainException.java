package com.project.coupon_service.exception;

import com.project.common.domain.exception.DomainException;

public class CustomDomainException extends DomainException {

    public CustomDomainException(String message) {
        super(message);
    }

    public CustomDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
