package com.project.userservice.exceptionHandling;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import com.project.common.exception.ApiError;
import com.project.common.exception.CustomRestExceptionHandler;

@ControllerAdvice
public class UserRestExceptionHandler extends CustomRestExceptionHandler {

  
        // 401 
        @ExceptionHandler(InsufficientAuthenticationException.class)
        public final ResponseEntity<Object> handleInsufficientAuthenticationExceptions(Exception ex, WebRequest request) {
            final List<String> error = new ArrayList<String>();
            error.add("Insufficient Authentication");
            final ApiError apiError = new ApiError(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.", error);
            return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
        }

        /**
         * 403 — 메서드 보안(@PreAuthorize 등)이 거절한 것. 예전에는 공통 처리기의 handleAll 로 떨어져 500 + ERROR 로그였다.
         * 문구는 일반 문구 — 거절 이유(어느 역할이 필요한가)는 내보내지 않는다.
         * [JA] 403 — メソッドセキュリティが拒否したもの。以前は共通処理器の handleAll に落ちて 500 + ERROR ログだった。
         */
        @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
        public final ResponseEntity<Object> handleAccessDenied(Exception ex, WebRequest request) {
            final ApiError apiError = new ApiError(HttpStatus.FORBIDDEN, "권한이 없습니다.", "access denied");
            return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
        }

}
