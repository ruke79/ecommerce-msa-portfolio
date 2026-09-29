package com.project.chat_service.common.exception.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

import com.project.common.exception.ClientMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * 🔒 예전에는 두 처리기 모두 {@code e.getMessage()} 를 그대로 실었다 — 500 이면 MongoDB·Redis 예외의 문구(호스트·
 * 쿼리·필드)가 클라이언트로 갔다. 문구는 {@link ClientMessage} 가 허용한 것만, 원래 문구는 로그로(docs/57 §1).
 */
@Slf4j
@RestControllerAdvice
public class ExceptionResponseHandler {
    @ExceptionHandler({IllegalArgumentException.class, NoSuchElementException.class})
    public ResponseEntity<ApiResponse> handleCommonException(Exception e) {
        log.info("요청 거절: {}: {}", e.getClass().getName(), e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ClientMessage.of(e), "002"));
    }


    /**
     * 우리가 사람에게 쓴 문구의 도메인 예외(채팅방 없음·회원 정보 없음 등) — 400 과 그 문구. 예전에는 같은 문구를
     * {@code IllegalStateException} 으로 던져 아래 500 "서버 오류" 로 떨어졌다(docs/57 §15).
     */
    @ExceptionHandler(com.project.common.domain.exception.DomainException.class)
    public ResponseEntity<ApiResponse> handleDomainException(com.project.common.domain.exception.DomainException e) {
        log.info("요청 거절: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ClientMessage.of(e), "002"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleUnexpectedException(Exception e) {
        log.error("처리되지 않은 예외: {}", e.getClass().getName(), e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("서버 오류가 발생했습니다. 잠시 뒤 다시 시도해 주세요.", "002"));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse> handleCustomException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode.getHttpStatus();
        String detail = errorCode.getDetail();
        String errorCodeName = errorCode.getErrorCode();

        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(detail, errorCodeName));

    }
}
