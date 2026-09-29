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

}
