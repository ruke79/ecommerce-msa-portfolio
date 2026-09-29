package com.project.chat_service.common.exception;

import com.project.common.domain.exception.DomainException;

public class UserDomainException extends DomainException {

    public UserDomainException(String message) {
        super(message);
    }

    public UserDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
