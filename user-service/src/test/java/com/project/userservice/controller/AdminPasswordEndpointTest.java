package com.project.userservice.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.project.userservice.service.UserService;

/**
 * 관리자 비밀번호 변경 — 비밀번호는 본문으로만 받는다.
 *
 * <p>🐛 예전: {@code @RequestParam password} — 쿼리스트링으로 보내도 받아, 접근 로그 · 프록시 로그에 평문 비밀번호가 남을 수 있었다.
 *
 * <p>[JA] 管理者のパスワード変更 — パスワードは本文でのみ受ける。以前はクエリ文字列でも受けていた。
 */
@DisplayName("관리자 비밀번호 변경 — 본문으로만")
class AdminPasswordEndpointTest {

    private final UserService users = mock(UserService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminController(users)).build();

    @Test
    @DisplayName("🔴 쿼리스트링의 비밀번호는 받지 않는다 — 400, 바꾸지 않는다")
    void passwordInQueryIsRejected() throws Exception {
        mvc.perform(put("/api/admin/update-password").param("userId", "7").param("password", "Secret-pw1"))
                .andExpect(status().isBadRequest());
        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("본문의 비밀번호로 바꾼다")
    void passwordInBodyIsApplied() throws Exception {
        mvc.perform(put("/api/admin/update-password").param("userId", "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Secret-pw1\"}"))
                .andExpect(status().isOk());
        verify(users).updatePassword(7L, "Secret-pw1");
    }
}
