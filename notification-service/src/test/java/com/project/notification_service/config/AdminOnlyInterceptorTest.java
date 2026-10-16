package com.project.notification_service.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 전체 공지(관리자 경로) 인가 — 경로를 인코딩하거나 {@code ;…} 를 붙여도 관리자만.
 *
 * <p>[JA] 全体告知(管理者パス)の認可 — パスをエンコードしたり {@code ;…} を付けても管理者だけ。
 */
@DisplayName("관리자 경로 인가 (AdminOnlyInterceptor)")
class AdminOnlyInterceptorTest {

    private final AdminOnlyInterceptor interceptor = new AdminOnlyInterceptor();

    private static MockHttpServletRequest req(String method, String uri, String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (userId != null) request.addHeader(AdminOnlyInterceptor.USER_HEADER, userId);
        if (role != null) request.addHeader(AdminOnlyInterceptor.ROLE_HEADER, role);
        return request;
    }

    @Test
    @DisplayName("일반 경로는 그대로 통과, 관리자 경로는 신원 없으면 401 · 일반 사용자 403 · 관리자 통과")
    void basicRules() throws Exception {
        assertTrue(interceptor.preHandle(req("GET", "/api/notifications", "42", "ROLE_USER"), new MockHttpServletResponse(), null));

        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("GET", "/api/notifications/admin/broadcasts", null, null), anonymous, null));
        assertEquals(401, anonymous.getStatus());

        MockHttpServletResponse user = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("POST", "/api/notifications/admin/broadcasts", "42", "ROLE_USER"), user, null));
        assertEquals(403, user.getStatus());

        assertTrue(interceptor.preHandle(req("POST", "/api/notifications/admin/broadcasts", "1", "ROLE_ADMIN"),
                new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("🔴 인코딩 · ;매개변수 · 겹슬래시로 적은 관리자 경로도 관리자 전용 — 예전엔 일반 사용자가 통과했다")
    void encodedAdminPathsAreAdminOnly() throws Exception {
        for (String uri : List.of("/api/notifications/%61dmin/broadcasts", "/api/notifications;x=1/admin/broadcasts",
                "/api/notifications/admin;v=2/broadcasts", "//api/notifications/admin/broadcasts",
                "/api/%6eotifications/admin/broadcasts")) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(req("POST", uri, "42", "ROLE_USER"), res, null), uri);
            assertEquals(403, res.getStatus(), uri);
        }
    }

    @Test
    @DisplayName("🔴 실제 디스패처로 — 인코딩한 경로가 공지 핸들러에 닿아도 인터셉터가 막는다")
    void dispatcherRoutesEncodedPathButInterceptorBlocks() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BroadcastProbe()).addInterceptors(interceptor).build();
        for (String uri : List.of("/api/notifications/%61dmin/broadcasts", "/api/notifications;x=1/admin/broadcasts")) {
            mvc.perform(post(new URI(uri)).header(AdminOnlyInterceptor.USER_HEADER, "42")
                    .header(AdminOnlyInterceptor.ROLE_HEADER, "ROLE_USER")).andExpect(status().isForbidden());
            mvc.perform(get(new URI(uri)).header(AdminOnlyInterceptor.USER_HEADER, "42")
                    .header(AdminOnlyInterceptor.ROLE_HEADER, "ROLE_USER")).andExpect(status().isForbidden());
            mvc.perform(post(new URI(uri)).header(AdminOnlyInterceptor.USER_HEADER, "1")
                    .header(AdminOnlyInterceptor.ROLE_HEADER, "ROLE_ADMIN")).andExpect(status().isOk());
        }
    }

    @RestController
    @RequestMapping("/api/notifications/admin/broadcasts")
    static class BroadcastProbe {
        @PostMapping
        String create() {
            return "created";
        }

        @GetMapping
        String list() {
            return "scheduled";
        }
    }
}
