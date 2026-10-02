package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletResponse;

import com.project.userservice.constants.TokenType;
import com.project.userservice.security.request.PasswordRequest;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.impl.UserServiceImpl;

import jakarta.servlet.http.Cookie;

/**
 * 🔴 비밀번호를 바꾸면 다른 기기는 끊기고 <b>이 기기는 이어진다</b>(docs/68 §14).
 *
 * <p>🐛 예전: 응답은 "다른 기기에서는 다시 로그인" 이었지만 이 기기도 몇 분 뒤 튕겼다 — 새 토큰을 주지 않았다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("비밀번호 변경 — 다른 기기는 끊고 이 기기는 새 로그인")
class PasswordChangeSessionTest {

    @Mock private UserServiceImpl userService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtUtils jwtUtils;
    @InjectMocks private UserController controller;

    private UserDetailsImpl me;
    private PasswordRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        me = mock(UserDetailsImpl.class);
        when(me.getId()).thenReturn(7L);
        request = mock(PasswordRequest.class);
        when(request.getCurrent_password()).thenReturn("old");
        when(request.getNew_password()).thenReturn("new-Passw0rd!");
        response = new MockHttpServletResponse();
        when(refreshTokenService.createRefreshToken(7L)).thenReturn(new RefreshToken("s", "a@example.com", "fresh.refresh"));
        when(jwtUtils.refreshTokenLifetime()).thenReturn(Duration.ofDays(14));
        when(jwtUtils.createCookie(anyString(), any(), any(Duration.class))).thenAnswer(inv -> {
            Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1));
            c.setMaxAge((int) ((Duration) inv.getArgument(2)).getSeconds());
            return c;
        });
    }

    @Test
    @DisplayName("🔴 모든 로그인을 끊은 뒤 이 기기에 새 로그인을 쿠키로 준다")
    void currentDeviceContinues() {
        var res = controller.updatePassword(request, me, response);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        var order = inOrder(userService, refreshTokenService);
        order.verify(userService).updatePassword(7L, "old", "new-Passw0rd!");
        order.verify(refreshTokenService).revokeAll(7L);
        order.verify(refreshTokenService).createRefreshToken(7L);
        assertThat(response.getCookie(TokenType.REFRESH.getType()).getValue()).isEqualTo("fresh.refresh");
    }

    @Test
    @DisplayName("현재 비밀번호가 틀려 실패하면 아무 로그인도 끊지 않는다")
    void failureKeepsSessions() {
        doThrow(new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.")).when(userService)
                .updatePassword(anyLong(), anyString(), anyString());

        var res = controller.updatePassword(request, me, response);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(refreshTokenService, never()).revokeAll(anyLong());
        assertThat(response.getCookie(TokenType.REFRESH.getType())).isNull();
    }
}
