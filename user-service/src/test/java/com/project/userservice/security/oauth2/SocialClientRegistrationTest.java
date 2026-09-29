package com.project.userservice.security.oauth2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("소셜 로그인 등록 — 자격증명이 있는 프로바이더만")
class SocialClientRegistrationTest {

    private SocialClientRegistration registration(String kakaoId, String kakaoSecret) {
        SocialClientRegistration r = new SocialClientRegistration();
        ReflectionTestUtils.setField(r, "publicUrl", "https://www.example.com");
        for (String f : List.of("googleClientId", "googleClientSecret", "githubClientId", "githubClientSecret",
                "facebookClientId", "facebookClientSecret")) {
            ReflectionTestUtils.setField(r, f, "");
        }
        ReflectionTestUtils.setField(r, "kakaoClientId", kakaoId);
        ReflectionTestUtils.setField(r, "kakaoClientSecret", kakaoSecret);
        ReflectionTestUtils.setField(r, "lineClientId", "");
        ReflectionTestUtils.setField(r, "lineClientSecret", "");
        return r;
    }

    @Test
    @DisplayName("비어 있으면 아무것도 등록하지 않는다 — 화면의 버튼도 사라진다")
    void nothingWhenEmpty() {
        assertTrue(registration("", "").configuredRegistrations().isEmpty());
        // 시크릿만 빠져도 등록하지 않는다 — 카카오는 Client Secret 을 켜 두면 토큰 요청에 꼭 실어야 한다
        assertTrue(registration("rest-api-key", "").configuredRegistrations().isEmpty());
    }

    @Test
    @DisplayName("🔴 카카오 — 콘솔에 등록하는 redirect URI 와 한 글자도 다르면 안 된다 · 시크릿은 본문(post)으로")
    void kakaoRegistration() {
        List<ClientRegistration> list = registration("rest-api-key", "secret").configuredRegistrations();

        assertEquals(1, list.size());
        ClientRegistration k = list.get(0);
        assertEquals("kakao", k.getRegistrationId());
        assertEquals("https://www.example.com/user-service/login/oauth2/code/kakao", k.getRedirectUri());
        assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_POST, k.getClientAuthenticationMethod());
        assertEquals("https://kauth.kakao.com/oauth/authorize", k.getProviderDetails().getAuthorizationUri());
        assertEquals("https://kauth.kakao.com/oauth/token", k.getProviderDetails().getTokenUri());
        assertEquals("https://kapi.kakao.com/v2/user/me", k.getProviderDetails().getUserInfoEndpoint().getUri());
        assertEquals("id", k.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
        // 이메일이 없으면 계정을 만들 수 없다 — 동의항목에 account_email 이 빠지면 모든 카카오 로그인이 거절된다
        assertTrue(k.getScopes().contains("account_email"));
    }

    @Test
    @DisplayName("🔴 LINE — OIDC 여야 이메일이 온다(ID 토큰에만 있다) · 발급자·JWKS 로 ID 토큰을 검증한다")
    void lineRegistration() {
        SocialClientRegistration r = registration("", "");
        ReflectionTestUtils.setField(r, "lineClientId", "1650000000");
        ReflectionTestUtils.setField(r, "lineClientSecret", "channel-secret");

        List<ClientRegistration> list = r.configuredRegistrations();

        assertEquals(1, list.size());
        ClientRegistration l = list.get(0);
        assertEquals("line", l.getRegistrationId());
        assertEquals("https://www.example.com/user-service/login/oauth2/code/line", l.getRedirectUri());
        assertTrue(l.getScopes().containsAll(List.of("openid", "email")));
        assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_POST, l.getClientAuthenticationMethod());
        assertEquals("https://access.line.me", l.getProviderDetails().getIssuerUri());
        assertEquals("https://api.line.me/oauth2/v2.1/certs", l.getProviderDetails().getJwkSetUri());
        assertEquals("sub", l.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
    }
}
