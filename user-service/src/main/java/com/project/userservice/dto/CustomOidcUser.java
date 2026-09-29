package com.project.userservice.dto;

import java.util.Map;

import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * OIDC 로 들어온 소셜 사용자(구글·LINE). 스프링의 OIDC 경로는 {@link OidcUser} 를 요구하고, 성공 핸들러는
 * {@link CustomOAuth2User} 를 기대한다 — 둘 다 만족시키려고 이어 붙였다. 신원(이메일·역할)은 부모가, ID 토큰·클레임은
 * 스프링이 검증한 원본이 갖는다.
 */
public class CustomOidcUser extends CustomOAuth2User implements OidcUser {

    private final OidcUser oidcUser;

    public CustomOidcUser(CustomOAuth2User resolved, OidcUser oidcUser) {
        super(resolved);
        this.oidcUser = oidcUser;
    }

    /** 부모는 속성을 들고 있지 않다(null) — OIDC 쪽은 스프링이 검증한 클레임을 그대로 보인다. */
    @Override
    public Map<String, Object> getAttributes() {
        return oidcUser.getAttributes();
    }

    @Override
    public Map<String, Object> getClaims() {
        return oidcUser.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return oidcUser.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return oidcUser.getIdToken();
    }
}
