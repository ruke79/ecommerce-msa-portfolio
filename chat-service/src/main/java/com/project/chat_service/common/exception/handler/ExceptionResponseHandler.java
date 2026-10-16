package com.project.chat_service.common.exception.handler;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
// 🐛 chat 은 com.project.common 도 스캔해 common 의 CustomRestExceptionHandler 도 함께 등록된다. 예전에는 둘 다 순서가 없고 둘 다
// Exception 전체를 받아, 빈 등록 순서가 결과를 정했다 — chat 이 먼저면 잘못된 요청(타입 불일치 · 필수 값 누락 · 깨진 JSON · 405 ·
// 415)이 모두 500 + ERROR 로그였고, common 이 먼저면 chat 의 도메인 예외 응답 모양이 바뀌었다. 이제 chat 의 것을 먼저 보고
// (아는 예외만), 나머지는 common 이 맡는다 — Exception 전체 받기를 여기서 뺐다.
// [JA] chat は common もスキャンするので common の処理器も登録される。両方とも順序がなく Exception 全体を受けていたため、
//      登録順で結果が決まっていた。chat のものを先に(知っている例外だけ)、残りは common に任せる。
@Order(Ordered.HIGHEST_PRECEDENCE)
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
