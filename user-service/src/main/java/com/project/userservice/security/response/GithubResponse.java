package com.project.userservice.security.response;

import java.util.Map;

/**
 * GitHub {@code /user} 응답.
 *
 * <p>⚠️ {@code email} 은 **자주 null 이다** — 프로필 이메일을 비공개로 둔 계정(다수)이
 * 그렇다. null 이면 {@code CustomOAuth2UserService} 가 {@code /user/emails} 를 따로
 * 호출해 primary+verified 주소를 찾는다(user:email scope 필요). 여기서는 있는 그대로
 * 돌려준다 — 폴백을 파서에 숨기면 토큰이 필요한 HTTP 호출이 생겨 테스트할 수 없어진다.
 */
public class GithubResponse implements OAuth2Response {

    private final Map<String, Object> attribute;

    public GithubResponse(Map<String, Object> attribute) {
        this.attribute = attribute;
    }

    @Override
    public String getProvider() {
        return "github";
    }

    @Override
    public String getProviderId() {
        return String.valueOf(attribute.get("id"));
    }

    @Override
    public String getEmail() {
        Object email = attribute.get("email");
        return email != null ? email.toString() : null;
    }

    @Override
    public String getName() {
        // name 을 안 채운 계정이 많다 — login(핸들)으로 폴백한다.
        Object name = attribute.get("name");
        if (name != null && !name.toString().isBlank()) {
            return name.toString();
        }
        return String.valueOf(attribute.get("login"));
    }
}
