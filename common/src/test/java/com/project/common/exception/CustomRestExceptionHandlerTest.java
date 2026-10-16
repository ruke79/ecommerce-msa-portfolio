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

    // ── 실제 디스패치(MockMvc) — 어느 처리기가 고르는가 ───────────────────────

    @org.springframework.web.bind.annotation.RestController
    static class Throwing {
        @org.springframework.web.bind.annotation.GetMapping("/domain")
        String domain() {
            throw new com.project.common.domain.exception.DomainException("쿠폰 사용 기간이 아닙니다.");
        }

        @org.springframework.web.bind.annotation.GetMapping("/conflict")
        String conflict() {
            throw new com.project.common.domain.exception.StateConflictException("이미 처리된 주문입니다.");
        }
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Throwing())
                .setControllerAdvice(handler).build();
    }

    @Test
    @DisplayName("🔴 도메인 예외는 400 과 그 문구 — 예전에는 handleAll 로 떨어져 500 + ERROR 로그였다")
    void domainExceptionIs400() throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/domain"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("쿠폰 사용 기간이 아닙니다."));
    }

    @Test
    @DisplayName("🔴 상태 충돌은 409 — StateConflictException 의 약속(주석)대로")
    void stateConflictIs409() throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/conflict"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value("이미 처리된 주문입니다."));
    }
}
