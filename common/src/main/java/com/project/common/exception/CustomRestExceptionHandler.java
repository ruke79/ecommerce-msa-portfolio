package com.project.common.exception;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class CustomRestExceptionHandler extends ResponseEntityExceptionHandler {

    // 400

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(final MethodArgumentNotValidException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final List<String> errors = new ArrayList<String>();
        for (final FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(error.getField() + ": " + error.getDefaultMessage());
        }
        for (final ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            errors.add(error.getObjectName() + ": " + error.getDefaultMessage());
        }
        // ex.getLocalizedMessage() 는 "Validation failed for argument [0] in public ... Controller.method(...)" —
        // 컨트롤러의 메서드 시그니처다. 무엇이 틀렸는지는 errors 가 말한다.
        // [JA] ex.getLocalizedMessage() は "Validation failed for argument [0] in public ... Controller.method(...)" —
        //      コントローラーのメソッドシグネチャである。何が間違っているかは errors が示す。
        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.", errors);
        return handleExceptionInternal(ex, apiError, headers, apiError.getStatus(), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final String error = ex.getPropertyName() + " should be of type " + simpleName(ex.getRequiredType());

        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청 값의 형식이 올바르지 않습니다.", error);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(final MissingServletRequestPartException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final String error = ex.getRequestPartName() + " part is missing";
        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청에 필요한 파일이 없습니다.", error);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(final MissingServletRequestParameterException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final String error = ex.getParameterName() + " parameter is missing";
        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청에 필요한 값이 없습니다.", error);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    //

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class })
    public ResponseEntity<Object> handleMethodArgumentTypeMismatch(final MethodArgumentTypeMismatchException ex, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final String error = ex.getName() + " should be of type " + simpleName(ex.getRequiredType());

        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청 값의 형식이 올바르지 않습니다.", error);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    @ExceptionHandler({ ConstraintViolationException.class })
    public ResponseEntity<Object> handleConstraintViolation(final ConstraintViolationException ex, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final List<String> errors = new ArrayList<String>();
        for (final ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            // 예전에는 앞에 루트 빈의 전체 클래스 이름(com.project....Controller)을 붙였다.
            // [JA] 以前は先頭にルート Bean の完全クラス名(com.project....Controller)を付けていた。
            errors.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }

        final ApiError apiError = new ApiError(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.", errors);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

         

    // 404

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(final NoHandlerFoundException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final String error = "No handler found for " + ex.getHttpMethod() + " " + ex.getRequestURL();

        final ApiError apiError = new ApiError(HttpStatus.NOT_FOUND, "찾을 수 없습니다.", error);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    // 405

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(final HttpRequestMethodNotSupportedException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final StringBuilder builder = new StringBuilder();
        builder.append(ex.getMethod());
        builder.append(" method is not supported for this request. Supported methods are ");
        ex.getSupportedHttpMethods().forEach(t -> builder.append(t + " "));

        final ApiError apiError = new ApiError(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.", builder.toString());
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    // 415

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(final HttpMediaTypeNotSupportedException ex, final HttpHeaders headers, final HttpStatusCode status, final WebRequest request) {
        logger.info(ex.getClass().getName());
        //
        final StringBuilder builder = new StringBuilder();
        builder.append(ex.getContentType());
        builder.append(" media type is not supported. Supported media types are ");
        ex.getSupportedMediaTypes().forEach(t -> builder.append(t + " "));

        final ApiError apiError = new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식입니다.", builder.substring(0, builder.length() - 2));
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    /** 사람에게 보이려고 만든 예외 — 코드가 정한 상태와 문구 그대로. 예전에는 아래 handleAll 로 떨어져 500 이었다. */
    // [JA] 人に見せるために作った例外 — コードが決めたステータスと文言をそのまま返す。以前は下の handleAll に落ちて 500 になっていた。
    @ExceptionHandler({ CustomException.class })
    public ResponseEntity<Object> handleCustom(final CustomException ex, final WebRequest request) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = code == null ? HttpStatus.INTERNAL_SERVER_ERROR : code.getHttpStatus();
        String message = code == null ? ClientMessage.DEFAULT : code.getDetail();
        final ApiError apiError = new ApiError(status, message, code == null ? "error occurred" : code.getErrorCode());
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    // 500

    /**
     * 🔒 예전에는 {@code ex.getLocalizedMessage()} 를 본문에 실었다 — JDBC 예외면 SQL·제약 이름·입력값이,
     * NPE 면 코드 구조가 클라이언트로 갔다. 문구는 {@link ClientMessage} 가 허용한 것만 싣고 나머지는 일반
     * 문구다. 원래 문구와 스택은 로그에 남는다(docs/57 §1).
     *
     * <p>[JA] 🔒 以前は {@code ex.getLocalizedMessage()} をボディに載せていた — JDBC 例外なら SQL・制約名・入力値が、
     * NPE ならコード構造がクライアントに渡っていた。文言は {@link ClientMessage} が許可したものだけを載せ、それ以外は一般的な
     * 文言にする。元の文言とスタックはログに残る(docs/57 §1)。
     */
    @ExceptionHandler({ Exception.class })
    public ResponseEntity<Object> handleAll(final Exception ex, final WebRequest request) {
        logger.error("처리되지 않은 예외: " + ex.getClass().getName(), ex);
        final ApiError apiError = new ApiError(HttpStatus.INTERNAL_SERVER_ERROR,
                ClientMessage.of(ex, "서버 오류가 발생했습니다. 잠시 뒤 다시 시도해 주세요."), "error occurred");
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }

    private static String simpleName(Class<?> type) {
        return type == null ? "?" : type.getSimpleName();
    }

}
