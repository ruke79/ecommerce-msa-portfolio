package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.project.common.constants.TokenStatus;
import com.project.userservice.constants.TokenType;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.impl.UserServiceImpl;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.Cookie;

/**
 * 🐛 <b>로그인 전 첫 로딩마다 콘솔에 400 "refresh token null" 이 찍혔다.</b>
 *
 * <p>프론트는 첫 화면을 그리기 전에 항상 재발급을 부른다(새로고침 뒤 세션 복구). 서버는
 * "쿠키가 하나도 없으면 204, 있는데 리프레시만 없으면 400" 이었는데, 운영에서는 쿠키가
 * 하나도 없는 요청이 거의 없다(Cloudflare 쿠키, 로그아웃 뒤 남은 쿠키). 그래서 방문자
 * 전원이 400 을 받았다. 세션이 없는 것은 오류가 아니다 — 204 다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("토큰 재발급 — 세션이 없는 방문자")
class RefreshTokenNoSessionTest {

    @Mock private UserServiceImpl userService;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;

    private RefreshTokenController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        controller = new RefreshTokenController(userService, jwtUtils, refreshTokenService);
        request = new MockHttpServletRequest("GET", "/api/token/refresh");
        response = new MockHttpServletResponse();
        // createCookie 는 진짜 것과 같은 모양으로 — 지우는 쿠키의 속성을 검사한다.
        when(jwtUtils.createCookie(anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(java.time.Duration.ZERO)))
                .thenAnswer(inv -> {
                    Cookie c = new Cookie(inv.getArgument(0), null);
                    c.setMaxAge(0);
                    c.setPath("/");
                    c.setHttpOnly(true);
                    c.setSecure(true);
                    return c;
                });
    }

    @Test
    @DisplayName("쿠키가 하나도 없으면 204")
    void noCookiesAtAll() throws Exception {
        ResponseEntity<?> r = controller.refreshtoken(request, response);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(jwtUtils, refreshTokenService, userService);
    }

    /** 🔴 핵심 — 예전에는 여기가 400 이었다. 다른 쿠키(Cloudflare 등)는 세션과 무관하다. */
    @Test
    @DisplayName("다른 쿠키만 있고 리프레시 쿠키가 없어도 204 — 400 이 아니다")
    void otherCookiesButNoRefresh() throws Exception {
        request.setCookies(new Cookie("__cf_bm", "abc"), new Cookie("locale", "ko"));
        ResponseEntity<?> r = controller.refreshtoken(request, response);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(r.getBody()).isNull();
        verifyNoInteractions(jwtUtils, refreshTokenService, userService);
    }

    @Test
    @DisplayName("리프레시 쿠키가 빈 값이면 없는 것과 같다 — 204")
    void blankRefreshCookie() throws Exception {
        request.setCookies(new Cookie(TokenType.REFRESH.getType(), ""));
        ResponseEntity<?> r = controller.refreshtoken(request, response);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(jwtUtils);
    }

    /** 만료는 400 그대로(프론트가 문구로 구분한다) — 대신 쿠키를 지워 다음 로딩부터 204 가 되게. */
    @Test
    @DisplayName("만료된 리프레시 쿠키는 400 'refresh token expired' 이고 쿠키를 지운다")
    void expiredCookieIsClearedAnd400() throws Exception {
        request.setCookies(new Cookie(TokenType.REFRESH.getType(), "expired.jwt"));
        doThrow(new ExpiredJwtException(null, null, "expired")).when(jwtUtils).isJwtTokenExpired("expired.jwt");

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo("refresh token expired");
        Cookie cleared = response.getCookie(TokenType.REFRESH.getType());
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo("/");
        verify(jwtUtils).createCookie(TokenType.REFRESH.getType(), null, java.time.Duration.ZERO);
    }

    @Test
    @DisplayName("위조된 리프레시 쿠키는 400 'invalid refresh token' 이고 쿠키를 지운다")
    void invalidCookieIsClearedAnd400() throws Exception {
        request.setCookies(new Cookie(TokenType.REFRESH.getType(), "forged.jwt"));
        when(jwtUtils.validateJwtToken("forged.jwt")).thenReturn(TokenStatus.INVALID);

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo("invalid refresh token");
        assertThat(response.getCookie(TokenType.REFRESH.getType()).getMaxAge()).isZero();
        verifyNoInteractions(refreshTokenService);
    }
}
