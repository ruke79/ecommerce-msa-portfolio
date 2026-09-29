package com.project.userservice.security.response;

import java.util.Map;

/**
 * LINE 로그인 v2.1 — OIDC 클레임(ID 토큰 + userinfo 를 합친 것).
 *
 * <pre>
 * { "sub": "U4af4980629...", "name": "山田太郎", "picture": "https://...", "email": "taro@example.jp" }
 * </pre>
 *
 * <p>⚠️ <b>이메일은 ID 토큰에만 있고, 채널에 "이메일 주소 권한" 신청이 승인돼야 실린다.</b> userinfo 엔드포인트와
 * 프로필 API 는 이메일을 주지 않는다 — 그래서 LINE 은 OAuth2 가 아니라 OIDC({@code openid} scope)로 등록한다.
 * 권한이 없거나 사용자가 이메일 제공에 동의하지 않으면 null 이고, {@code CustomOAuth2UserService} 가
 * {@code email_required} 로 거절한다.
 */
public class LineResponse implements OAuth2Response {

    private final Map<String, Object> claims;

    public LineResponse(Map<String, Object> claims) {
        this.claims = claims;
    }

    @Override
    public String getProvider() {
        return "line";
    }

    @Override
    public String getProviderId() {
        return String.valueOf(claims.get("sub"));
    }

    @Override
    public String getEmail() {
        Object email = claims.get("email");
        return email != null && !email.toString().isBlank() ? email.toString() : null;
    }

    @Override
    public String getName() {
        Object name = claims.get("name");
        if (name != null && !name.toString().isBlank()) {
            return name.toString();
        }
        String email = getEmail();
        return email != null && email.indexOf('@') > 0 ? email.substring(0, email.indexOf('@')) : "line_" + getProviderId();
    }
}
