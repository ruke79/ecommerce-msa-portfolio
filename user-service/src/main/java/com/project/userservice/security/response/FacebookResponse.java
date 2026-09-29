package com.project.userservice.security.response;

import java.util.Map;

/**
 * Facebook Graph {@code /me?fields=id,name,email} 응답.
 *
 * <p>email 은 사용자가 앱 권한에서 이메일 제공을 거부했거나 전화번호로만 가입한 계정이면
 * 없을 수 있다 — null 이면 {@code CustomOAuth2UserService} 가 로그인 자체를 거절한다
 * (이 저장소의 사용자 식별자가 이메일이라 없으면 만들 수 없다).
 */
public class FacebookResponse implements OAuth2Response {

    private final Map<String, Object> attribute;

    public FacebookResponse(Map<String, Object> attribute) {
        this.attribute = attribute;
    }

    @Override
    public String getProvider() {
        return "facebook";
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
        return String.valueOf(attribute.get("name"));
    }
}
