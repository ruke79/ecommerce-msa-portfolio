package com.project.chat_service.common.exception.handler;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;
}
