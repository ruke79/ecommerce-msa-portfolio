package com.project.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 공통 예외 처리기의 500 이 내부 문구를 싣지 않는다 (docs/57 §1).
 */
@DisplayName("CustomRestExceptionHandler — 응답 본문의 문구")
class CustomRestExceptionHandlerTest {

    private final CustomRestExceptionHandler handler = new CustomRestExceptionHandler();

    private static ApiError body(ResponseEntity<Object> response) {
        return (ApiError) response.getBody();
    }

    @Test
    @DisplayName("🔴 처리되지 않은 JDBC 예외 — 500 이고, SQL·제약 이름은 본문에 없다")
    void dataAccessIsGeneric() {
        ResponseEntity<Object> response = handler.handleAll(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_coupon_user\" Key (user_id)=(42)"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).getMessage()).doesNotContain("constraint", "uk_coupon_user", "42");
        assertThat(String.join(" ", body(response).getErrors())).doesNotContain("constraint");
    }

    @Test
    @DisplayName("우리가 사람에게 쓴 문구는 그대로 — 화면이 이 문구를 보여 준다")
    void ownMessageKept() {
        ResponseEntity<Object> response = handler.handleAll(
                new RuntimeException("링크의 유효기간이 지났습니다. 비밀번호 찾기를 다시 요청해 주세요."), null);

        assertThat(body(response).getMessage()).isEqualTo("링크의 유효기간이 지났습니다. 비밀번호 찾기를 다시 요청해 주세요.");
    }

    @Test
    @DisplayName("🐛 CustomException 은 500 이 아니라 ErrorCode 의 상태다")
    void customExceptionUsesErrorCode() {
        ResponseEntity<Object> response = handler.handleCustom(new CustomException(ErrorCode.USER_NOT_FOUND), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(response).getMessage()).isEqualTo("해당 회원 정보를 찾을 수 없습니다.");
    }
}
