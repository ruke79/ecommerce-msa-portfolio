package com.project.userservice.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.userservice.exceptionHandling.UserRestExceptionHandler;

/**
 * user-service 의 예외 응답 — 메서드 보안이 거절하면 403. 예전에는 공통 처리기의 handleAll 로 떨어져 500 + ERROR 로그였다.
 *
 * <p>[JA] user-service の例外応答 — メソッドセキュリティが拒否したら 403。以前は 500 + ERROR ログだった。
 */
@DisplayName("user-service 예외 응답 — 권한 거절")
class UserRestExceptionHandlerTest {

    @RestController
    static class Guarded {
        @GetMapping("/admin-only")
        String adminOnly() {
            throw new AccessDeniedException("Access Denied");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Guarded())
            .setControllerAdvice(new UserRestExceptionHandler()).build();

    @Test
    @DisplayName("🔴 AccessDeniedException 은 500 이 아니라 403 + 일반 문구")
    void accessDeniedIs403() throws Exception {
        mvc.perform(get("/admin-only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    }
}
