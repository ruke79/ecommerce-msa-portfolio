package com.project.userservice.dto;

import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.security.response.OAuth2Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;



public class CustomOAuth2User implements OAuth2User {

    private final OAuth2Response oAuth2Response;
    private final String role;

    public CustomOAuth2User(OAuth2Response oAuth2Response, String role) {

        this.oAuth2Response = oAuth2Response;
        this.role = role;
    }

    /** {@link CustomOidcUser} 가 이미 확정된 신원을 그대로 물려받을 때 쓴다. */
    protected CustomOAuth2User(CustomOAuth2User resolved) {
        this(resolved.oAuth2Response, resolved.role);
    }

    @Override
    public Map<String, Object> getAttributes() {

        return null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {

        Collection<GrantedAuthority> collection = new ArrayList<>();

        collection.add(new GrantedAuthority() {

            @Override
            public String getAuthority() {

                return role;
            }
        });

        return collection;
    }

    @Override
    public String getName() {

        return oAuth2Response.getName();
    }

    public String getUsername() {

        return oAuth2Response.getProvider() + "_" + oAuth2Response.getProviderId();
    }

    /**
     * 성공 핸들러가 사용자 레코드를 찾는 키. 예전 핸들러는 getUsername() 결과를
     * findByEmail 에 넘기고 있었다 — "google 12345" 는 이메일이 아니라서 **모든 소셜
     * 로그인이 user_not_found 로 끝났다.** 이름과 내용이 어긋난 접근자가 만든 버그라,
     * 이메일은 이메일이라고 부른다.
     */
    public String getEmail() {
        return oAuth2Response.getEmail();
    }
}
