package com.project.userservice.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.constants.AppRole;
import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.response.OAuth2Response;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.Cookie;

/**
 * 소셜 로그인 마무리의 두 갈래를 고정한다:
 * 일반 계정 → REFRESH 쿠키 + 홈, 2FA 계정 → PENDING_2FA 쿠키 + 2FA 입력 화면.
 * (예전에는 2FA 계정을 아예 거절했고, 그 전에는 getUsername() 을 이메일 조회에 넘겨
 * 모든 소셜 로그인이 user_not_found 로 끝났다.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2SuccessHandlerTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private com.project.userservice.security.PendingTwoFactor pendingTwoFactor;

    @InjectMocks private OAuth2SuccessHandler handler;

    private User user;

    private record FakeResponse(String provider, String providerId, String email, String name)
            implements OAuth2Response {
        public String getProvider() { return provider; }
        public String getProviderId() { return providerId; }
        public String getEmail() { return email; }
        public String getName() { return name; }
    }

    private Authentication auth(String email) {
        CustomOAuth2User principal = new CustomOAuth2User(
                new FakeResponse("google", "g-1", email, "Kim"), "ROLE_USER");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "frontendUrl", "https://shop.example.com");

        user = new User("kim", "kim@example.com", "$2a$pw");
        user.setRole(new Role(AppRole.ROLE_USER));
        ReflectionTestUtils.setField(user, "userId", 42L);
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(user));

        when(jwtUtils.createCookie(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(inv -> {
                    Cookie c = new Cookie(inv.getArgument(0), inv.getArgument(1));
                    c.setMaxAge((int) ((java.time.Duration) inv.getArgument(2)).toSeconds());
                    return c;
                });
        when(jwtUtils.refreshTokenLifetime()).thenReturn(java.time.Duration.ofDays(1));
    }

    @Test
    @DisplayName("일반 계정: REFRESH 쿠키를 심고 홈으로 — 세션 복구는 기존 /api/token/refresh 경로가 한다")
    void normalAccountGetsRefreshCookie() throws Exception {
        RefreshToken token = mock(RefreshToken.class);
        when(token.getToken()).thenReturn("refresh-token");
        when(refreshTokenService.createRefreshToken(42L)).thenReturn(token);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, auth("kim@example.com"));

        assertNotNull(response.getCookie("REFRESH"));
        assertEquals("https://shop.example.com", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("2FA 계정: 세션 없이 PENDING_2FA(5분) 쿠키 + 2FA 입력 화면 — 소셜이 2FA 우회로가 되면 안 된다")
    void twoFactorAccountGetsPendingCookie() throws Exception {
        user.setTwoFactorEnabled(true);
        when(pendingTwoFactor.issue(42L)).thenReturn("pending-token");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, auth("kim@example.com"));

        assertNotNull(response.getCookie("PENDING_2FA"));
        assertNull(response.getCookie("REFRESH"));   // 코드 확인 전에는 세션이 없다
        assertEquals("https://shop.example.com/signin?oauth2fa=1", response.getRedirectedUrl());
        verify(refreshTokenService, never()).createRefreshToken(anyLong());
        // 🔴 대기 쿠키의 값이 액세스 토큰(같은 키의 보통 JWT)이면 게이트웨이에 Bearer 로 보내 2FA 를 건너뛴다.
        assertEquals("pending-token", response.getCookie("PENDING_2FA").getValue());
        verify(jwtUtils, never()).generateToken(anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyLong());
        verify(jwtUtils, never()).generateToken(anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
