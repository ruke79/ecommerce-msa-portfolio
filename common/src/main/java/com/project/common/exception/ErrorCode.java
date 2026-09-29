package com.project.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import static org.springframework.http.HttpStatus.*;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    /* 404 NOT_FOUND : Resource 를 찾을 수 없음 */
    USER_NOT_FOUND(NOT_FOUND, "해당 회원 정보를 찾을 수 없습니다.", "015"),
    
    UNAUTHORIZED_TOKEN_EXCEPTION(UNAUTHORIZED, "다시 채팅을 시작해주세요.", "028"),
    UN_SUPPORTED_FILE(UNSUPPORTED_MEDIA_TYPE, "지원하는 파일 형식이 아닙니다.", "001"),    
    MAX_FILE_SIZE_EXCEEDED(PAYLOAD_TOO_LARGE, "파일 크기가 허용되는 최대치를 초과하였습니다.", "002");

    private final HttpStatus httpStatus;
    private final String detail;
    private final String errorCode;

    
    
    
    
    public static CustomException throwUnSupportedFile() {
        throw new CustomException(UN_SUPPORTED_FILE);
    }
    
    public static CustomException throwMaxFileSizeExceeded() {
        throw new CustomException(MAX_FILE_SIZE_EXCEEDED);
    }

    public static CustomException throwUserNotFound() {
        throw new CustomException(USER_NOT_FOUND);
    }
    public static CustomException unauthorizedTokenException() {
        throw new CustomException(UNAUTHORIZED_TOKEN_EXCEPTION);
    }

}
