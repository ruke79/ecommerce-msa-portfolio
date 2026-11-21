package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.constants.AppRole;
import com.project.userservice.constants.TokenType;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.security.RedisSessionManager;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.service.DemoAccountService;
import com.project.userservice.service.RefreshTokenService;

/**
 * 데모 로그인 — 꺼져 있으면 404, 켜져 있으면 데모 표시가 실린 토큰(docs/100). 표시는 게이트웨이 DemoAccessPolicy 가 읽는다 — 빠지면
 * 데모 방문자가 비밀번호 · 2단계 인증 · 탈퇴를 할 수 있다.
 *
 * <p>[JA] デモログイン — 無効なら 404、有効ならデモ表示の載ったトークン。表示はゲートウェイが読む — 抜けるとデモ訪問者がパスワード ·
 *      2 段階認証 · 退会をできてしまう。
 */
@DisplayName("데모 로그인 — 404 · 토큰의 demo 표시")
class DemoLoginControllerTest {

    private static final String SECRET = "user-test-secret-user-test-secret-0123456";

    private static JwtUtils jwt() {
        JwtUtils j = new JwtUtils(mock(RedisSessionManager.class));
        ReflectionTestUtils.setField(j, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(j, "jwtExpirationMs", 60_000);
        ReflectionTestUtils.setField(j, "jwtRefreshExpirationMs", 120_000);
        return j;
    }

    private static User user(boolean demo) {
        User u = new User("demo_visitor_1", "demo1@demo.invalid", "pw");
        u.setUserId(7L);
        u.setRole(new Role(AppRole.ROLE_USER));
        u.setSignUpMethod(demo ? User.DEMO_SIGN_UP_METHOD : "email");
        return u;
    }

    /** 서명은 보지 않고 본문 JSON 만 — 클레임 확인용. [JA] 署名は見ず本文 JSON だけ。 */
    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("꺼져 있으면 404 — 계정을 고르지도 토큰을 만들지도 않는다")
    void disabledIs404() {
        DemoAccountService demo = mock(DemoAccountService.class);
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        var res = new DemoLoginController(demo, jwt(), refresh).demoLogin(new MockHttpServletResponse());
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(demo, never()).claimNext();
        verify(refresh, never()).createRefreshToken(anyLong());
    }

    @Test
    @DisplayName("화면용 상태 — 켜져 있는가만 알린다")
    void statusReportsEnabled() {
        DemoAccountService demo = mock(DemoAccountService.class);
        assertThat(new DemoLoginController(demo, jwt(), mock(RefreshTokenService.class)).demoLoginStatus()).containsEntry("enabled", false);
        when(demo.enabled()).thenReturn(true);
        assertThat(new DemoLoginController(demo, jwt(), mock(RefreshTokenService.class)).demoLoginStatus()).containsEntry("enabled", true);
    }

    @Test
    @DisplayName("내줄 계정이 없으면 503")
    void noAccountIs503() {
        DemoAccountService demo = mock(DemoAccountService.class);
        when(demo.enabled()).thenReturn(true);
        when(demo.claimNext()).thenReturn(Optional.empty());
        var res = new DemoLoginController(demo, jwt(), mock(RefreshTokenService.class)).demoLogin(new MockHttpServletResponse());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("🔴 켜져 있으면 200 · 액세스 토큰에 demo=true · 리프레시 쿠키")
    void issuesDemoToken() {
        DemoAccountService demo = mock(DemoAccountService.class);
        when(demo.enabled()).thenReturn(true);
        when(demo.claimNext()).thenReturn(Optional.of(user(true)));
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        RefreshToken rt = mock(RefreshToken.class);
        when(rt.getToken()).thenReturn("r");
        when(refresh.createRefreshToken(any())).thenReturn(rt);
        MockHttpServletResponse response = new MockHttpServletResponse();

        var res = new DemoLoginController(demo, jwt(), refresh).demoLogin(response);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        String access = response.getHeader(TokenType.ACCESS.getType());
        assertThat(payload(access)).contains("\"demo\":true");
        assertThat(response.getCookie(TokenType.REFRESH.getType())).isNotNull();
    }

    @Test
    @DisplayName("🔴 보통 계정의 토큰(갱신 경로 같은 메서드)에는 demo 표시가 없다")
    void regularUserHasNoDemoClaim() {
        assertThat(payload(jwt().generateTokenFromUser(user(false)))).doesNotContain("\"demo\"");
        assertThat(payload(jwt().generateTokenFromUser(user(true)))).contains("\"demo\":true");
    }
}
