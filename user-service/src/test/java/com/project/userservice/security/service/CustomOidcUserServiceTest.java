package com.project.userservice.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.dto.CustomOidcUser;
import com.project.userservice.security.response.GoogleResponse;

/**
 * 🔴 회귀 — openid scope 가 있는 등록(구글·LINE)은 스프링이 OIDC 경로로 처리한다. 예전에는 그 경로에 계정 연결이 없어
 * 사용자가 만들어지지 않았고, 성공 핸들러의 {@code (CustomOAuth2User)} 형 변환이 실패했다.
 */
@DisplayName("OIDC 소셜 로그인 — 계정 연결은 OAuth2 경로와 같은 규칙으로")
class CustomOidcUserServiceTest {

    private OidcUserRequest request(String registrationId) {
        ClientRegistration reg = ClientRegistration.withRegistrationId(registrationId)
                .clientId("id").clientSecret("s")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com/cb").scope("openid", "email")
                .authorizationUri("https://example.com/a").tokenUri("https://example.com/t")
                .jwkSetUri("https://example.com/jwks").userNameAttributeName("sub").build();
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("sub", "g-1", "email", "a@gmail.com", "email_verified", true, "name", "A"));
        return new OidcUserRequest(reg, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "tok",
                Instant.now(), Instant.now().plusSeconds(60)), idToken);
    }

    @Test
    @DisplayName("스프링이 검증한 클레임으로 resolve 를 부르고, 성공 핸들러가 기대하는 타입을 돌려준다")
    void resolvesThroughSharedRules() {
        CustomOAuth2UserService accounts = mock(CustomOAuth2UserService.class);
        CustomOidcUserService service = new CustomOidcUserService(accounts);
        OidcUserRequest req = request("google");
        OidcUser verified = new DefaultOidcUser(Set.of(), req.getIdToken());
        service.delegate = r -> verified;
        CustomOAuth2User resolved = new CustomOAuth2User(new GoogleResponse(verified.getClaims()), "ROLE_USER");
        when(accounts.resolve(eq("google"), anyMap(), eq("tok"))).thenReturn(resolved);

        OidcUser principal = service.loadUser(req);

        verify(accounts).resolve(eq("google"), argThat(c -> "a@gmail.com".equals(c.get("email"))), eq("tok"));
        assertInstanceOf(CustomOidcUser.class, principal);
        // 성공 핸들러는 이 형 변환으로 사용자를 찾는다 — 예전에는 여기서 ClassCastException
        assertEquals("a@gmail.com", ((CustomOAuth2User) principal).getEmail());
        assertEquals("id-token", principal.getIdToken().getTokenValue());
        assertTrue(principal.getAuthorities().stream().anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
    }
}
