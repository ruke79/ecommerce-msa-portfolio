package com.project.catalog_service.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 쓰기 인가 회귀 테스트.
 *
 * <p>배경: 이 서비스의 쓰기 API 전체(상품 등록·카테고리 생성·배치 잡 기동)가 무인증으로
 * 배포됐고, 사이트가 공개된 뒤에도 그 상태였다. 유일한 @PreAuthorize 는 주석 처리돼
 * 있었다. 이 테스트는 "쓰기는 기본이 관리자"라는 계약이 다시 조용히 풀리는 것을 막는다.
 */
class AdminWriteInterceptorTest {

    private final AdminWriteInterceptor interceptor = new AdminWriteInterceptor();

    private MockHttpServletRequest req(String method, String uri, String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (userId != null) request.addHeader(AdminWriteInterceptor.USER_HEADER, userId);
        if (role != null) request.addHeader(AdminWriteInterceptor.ROLE_HEADER, role);
        return request;
    }

    @Test
    @DisplayName("GET 은 신원 없이 통과 — 카탈로그 조회는 공개다")
    void readsArePublic() throws Exception {
        assertTrue(interceptor.preHandle(
                req("GET", "/api/product/frontpage", null, null), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("신원 헤더 없는 쓰기는 401 — 게이트웨이를 거치지 않은 요청이다")
    void writeWithoutIdentityIs401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("POST", "/api/product", null, null), res, null));
        assertEquals(401, res.getStatus());
    }

    @Test
    @DisplayName("일반 사용자의 쓰기는 403 — 로그인만으로는 상품을 만들 수 없다")
    void userRoleWriteIs403() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("POST", "/api/product", "42", "ROLE_USER"), res, null));
        assertEquals(403, res.getStatus());
    }

    @Test
    @DisplayName("관리자의 쓰기는 통과")
    void adminWritePasses() throws Exception {
        assertTrue(interceptor.preHandle(
                req("POST", "/api/product", "1", "ROLE_ADMIN"), new MockHttpServletResponse(), null));
        assertTrue(interceptor.preHandle(
                req("POST", "/api/products/sync", "1", "ROLE_ADMIN"), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("🔴 별점은 내부 전용 — 로그인한 일반 사용자가 직접 바꾸면 403 (docs/57 §3)")
    void ratingIsInternalOnly() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("PUT", "/api/product/rating", "42", "ROLE_USER"), res, null));
        assertEquals(403, res.getStatus());
    }

    @Test
    @DisplayName("🔴 신원 헤더 없는 내부 호출(user-service 의 리뷰 평균)은 통과 — 예전에는 401 이라 리뷰 저장까지 롤백됐다")
    void internalRatingPasses() throws Exception {
        assertTrue(interceptor.preHandle(
                req("PUT", "/api/product/rating", null, null), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("관리자는 별점을 고칠 수 있다(운영 보정)")
    void adminRatingPasses() throws Exception {
        assertTrue(interceptor.preHandle(
                req("PUT", "/api/product/rating", "1", "ROLE_ADMIN"), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("콘텐츠 관리 경로는 GET 도 관리자 전용 — 비공개 배너·초안 공지가 그 아래 있다")
    void contentAdminReadsRequireAdmin() throws Exception {
        // 로그인하지 않은 조회: 401. 쓰기만 막았다면 이 요청이 그냥 통과했다.
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                req("GET", "/api/content/admin/banners", null, null), anonymous, null));
        assertEquals(401, anonymous.getStatus());

        // 로그인한 일반 사용자의 조회: 403.
        MockHttpServletResponse user = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                req("GET", "/api/content/admin/notices", "42", "ROLE_USER"), user, null));
        assertEquals(403, user.getStatus());

        // 관리자는 통과.
        assertTrue(interceptor.preHandle(
                req("GET", "/api/content/admin/banners", "1", "ROLE_ADMIN"),
                new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("공개 콘텐츠 조회는 그대로 공개 — 배너는 첫 화면이고 공지는 비회원도 본다")
    void publicContentStaysPublic() throws Exception {
        assertTrue(interceptor.preHandle(
                req("GET", "/api/content/banners", null, null), new MockHttpServletResponse(), null));
        assertTrue(interceptor.preHandle(
                req("GET", "/api/content/notices/1", null, null), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("CORS preflight 는 막지 않는다 — 막으면 브라우저가 본 요청을 보내지도 못한다")
    void preflightPasses() throws Exception {
        assertTrue(interceptor.preHandle(
                req("OPTIONS", "/api/content/admin/banners", null, null),
                new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("역할 헤더를 위조해도 게이트웨이가 지운다는 전제의 문서화 — 값이 이상하면 403")
    void garbageRoleIs403() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                req("DELETE", "/api/category/category", "42", "ROLE_ADMIN2"), res, null));
        assertEquals(403, res.getStatus());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("🔒 내부 API(재고 예약)는 내부 호출·관리자만 — 로그인한 일반 사용자는 읽기도 403 (docs/59)")
    void internalApiIsInternalOnly() throws Exception {
        MockHttpServletResponse post = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("POST", "/api/internal/stock/reservations", "42", "ROLE_USER"), post, null));
        assertEquals(403, post.getStatus());
        MockHttpServletResponse get = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req("GET", "/api/internal/stock/reservations", "42", "ROLE_USER"), get, null));
        assertEquals(403, get.getStatus());

        assertTrue(interceptor.preHandle(req("POST", "/api/internal/stock/reservations", null, null), new MockHttpServletResponse(), null));
        assertTrue(interceptor.preHandle(req("POST", "/api/internal/stock/reservations/7/release", "1", "ROLE_ADMIN"), new MockHttpServletResponse(), null));
    }
}
