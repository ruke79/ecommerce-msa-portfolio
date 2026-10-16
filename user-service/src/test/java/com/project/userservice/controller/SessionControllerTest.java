package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetails;

import com.project.userservice.constants.TokenType;
import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.impl.UserServiceImpl;

import jakarta.servlet.http.Cookie;

/** 모든 기기에서 로그아웃 — 엔드포인트(docs/68 §8). 끊는 동작 자체는 RefreshTokenRotationTest(실제 Redis). */
@DisplayName("모든 기기에서 로그아웃 — 엔드포인트")
class SessionControllerTest {

    private UserServiceImpl users;
    private RefreshTokenService tokens;
    private JwtUtils jwt;
    private SessionController controller;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        users = mock(UserServiceImpl.class);
        tokens = mock(RefreshTokenService.class);
        jwt = mock(JwtUtils.class);
        controller = new SessionController(users, tokens, jwt);
        response = new MockHttpServletResponse();
        when(jwt.createCookie(anyString(), any(), any(Duration.class))).thenAnswer(inv -> {
            Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1));
            c.setMaxAge((int) ((Duration) inv.getArgument(2)).getSeconds());
            return c;
        });
    }

    private static UserDetails principal(String username) {
        UserDetails d = mock(UserDetails.class);
        when(d.getUsername()).thenReturn(username);
        return d;
    }

    /** 실제 principal — JwtAuthFilter 가 세우는 것과 같다. 가입 아이디(kim)와 이메일이 다르다. */
    private static UserDetailsImpl realPrincipal() {
        User u = new User("kim", "kim@example.com", "x");
        u.setUserId(7L);
        u.setRole(new Role(AppRole.ROLE_USER));
        return UserDetailsImpl.build(u);
    }

    @Test
    @DisplayName("🔴 토큰의 주인 것만 끊는다 — 몇 개를 끊었는지 돌려주고, 이 기기의 리프레시 쿠키도 지운다")
    void revokesTheCallersLogins() {
        User me = mock(User.class);
        when(me.getUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(me);
        when(tokens.revokeAll(7L)).thenReturn(3);

        var r = controller.revokeAll(realPrincipal(), response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(Map.of("revoked", 3));
        verify(tokens).revokeAll(7L);
        assertThat(response.getCookie(TokenType.REFRESH.getType()).getMaxAge()).isZero();
    }

    @Test
    @DisplayName("🔴 getUsername() 은 이메일이 아니다 — 그 값으로 이메일 조회를 하면 모든 사용자가 401 이었다")
    void doesNotLookUpTheUsernameAsAnEmail() {
        User me = mock(User.class);
        when(me.getUserId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(me);

        var r = controller.revokeAll(realPrincipal(), response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("로그인하지 않았으면 401 — 아무것도 끊지 않는다")
    void anonymousIsRejected() {
        var r = controller.revokeAll(null, response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(tokens, never()).revokeAll(anyLong());
    }

    @Test
    @DisplayName("토큰의 주인이 없으면(탈퇴 등) 401")
    void unknownUserIsRejected() {
        var r = controller.revokeAll(principal("gone@example.com"), response);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(tokens, never()).revokeAll(anyLong());
    }
}
