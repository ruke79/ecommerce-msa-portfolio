package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.project.userservice.model.User;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.RefreshTokenService.Kind;
import com.project.userservice.service.RefreshTokenService.Rotation;
import com.project.userservice.service.impl.UserServiceImpl;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 토큰 재발급 — 회전 결과를 응답으로 옮기는 부분(docs/68). 회전 자체는 {@code RefreshTokenRotationTest}(실제 Redis).
 *
 * <p>🐛 예전: 세션 키가 "있는가" 만 보고 {@code createRefreshToken} 으로 새 토큰을 새 키에 만들었다 — 옛 토큰은 그대로 살아 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("토큰 재발급 — 회전 결과 → 응답")
class RefreshTokenRotationControllerTest {

    private static final String PRESENTED = "presented.jwt";

    @Mock private UserServiceImpl userService;
    @Mock private JwtUtils jwtUtils;
    @Mock private RefreshTokenService refreshTokenService;

    private RefreshTokenController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private User owner;

    @BeforeEach
    void setUp() {
        controller = new RefreshTokenController(userService, jwtUtils, refreshTokenService);
        request = new MockHttpServletRequest("GET", "/api/token/refresh");
        request.setCookies(new Cookie(TokenType.REFRESH.getType(), PRESENTED));
        response = new MockHttpServletResponse();

        when(jwtUtils.validateJwtToken(PRESENTED)).thenReturn(TokenStatus.VALID);
        when(jwtUtils.isRefreshTokenType(PRESENTED)).thenReturn(true);
        when(jwtUtils.getIdFromJwtToken(PRESENTED)).thenReturn("a@example.com");
        owner = mock(User.class);
        when(owner.getUserId()).thenReturn(7L);
        when(userService.findByEmail("a@example.com")).thenReturn(owner);
        when(jwtUtils.generateTokenFromUser(owner)).thenReturn("new.access");
        // 쿠키는 새 토큰의 남은 수명으로 — 절대 수명(30일)이 가까우면 14일보다 짧다(docs/68 §12)
        when(jwtUtils.remainingLifetime(anyString())).thenReturn(Duration.ofDays(14));
        when(jwtUtils.createCookie(anyString(), any(), any(Duration.class))).thenAnswer(inv -> {
            Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1));
            c.setMaxAge((int) ((Duration) inv.getArgument(2)).getSeconds());
            return c;
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Kind.class, names = { "ROTATED", "GRACE" })
    @DisplayName("회전 성공(ROTATED · GRACE) — 200, 새 액세스 토큰, 쿠키에는 회전이 돌려준 토큰")
    void okSetsTheRotatedToken(Kind kind) throws Exception {
        when(refreshTokenService.rotate(PRESENTED, 7L)).thenReturn(new Rotation(kind, "next.refresh"));

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeader(TokenType.ACCESS.getType())).isEqualTo("new.access");
        Cookie c = response.getCookie(TokenType.REFRESH.getType());
        assertThat(c.getValue()).isEqualTo("next.refresh");
        assertThat(c.getMaxAge()).isPositive();
        // 🔴 컨트롤러가 따로 새 묶음을 만들지 않는다 — 예전 버그의 자리
        verify(refreshTokenService, never()).createRefreshToken(any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Kind.class, names = { "REUSED", "REVOKED", "REJECTED" })
    @DisplayName("🔴 재사용 · 끊긴 묶음 · 거절 — 400 'invalid refresh token', 쿠키를 지우고, 액세스 토큰은 없다")
    void refusedClearsTheCookie(Kind kind) throws Exception {
        when(refreshTokenService.rotate(PRESENTED, 7L)).thenReturn(new Rotation(kind, null));

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo("invalid refresh token");
        assertThat(response.getCookie(TokenType.REFRESH.getType()).getMaxAge()).isZero();
        assertThat(response.getHeader(TokenType.ACCESS.getType())).isNull();
        verify(jwtUtils, never()).generateTokenFromUser(any());
    }

    @Test
    @DisplayName("회전 이전 토큰(LEGACY) — 400 'refresh token expired'(프론트에는 만료와 같다), 쿠키를 지운다")
    void legacyLooksExpired() throws Exception {
        when(refreshTokenService.rotate(PRESENTED, 7L)).thenReturn(new Rotation(Kind.LEGACY, null));

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo("refresh token expired");
        verify(jwtUtils).createCookie(eq(TokenType.REFRESH.getType()), isNull(), eq(Duration.ZERO));
    }

    @Test
    @DisplayName("토큰 주인이 없으면(탈퇴 등) 회전하지 않고 400")
    void unknownOwnerIsRefused() throws Exception {
        when(userService.findByEmail("a@example.com")).thenReturn(null);

        ResponseEntity<?> r = controller.refreshtoken(request, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(refreshTokenService, never()).rotate(anyString(), any());
    }
}
