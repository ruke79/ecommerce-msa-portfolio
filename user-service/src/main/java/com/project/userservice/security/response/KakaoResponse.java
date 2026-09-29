package com.project.userservice.security.response;

import java.util.Map;

/**
 * 카카오 {@code GET https://kapi.kakao.com/v2/user/me} 응답.
 *
 * <pre>
 * { "id": 1234567890,
 *   "kakao_account": {
 *       "email": "a@kakao.com", "is_email_valid": true, "is_email_verified": true,
 *       "profile": { "nickname": "홍길동" } },
 *   "properties": { "nickname": "홍길동" } }
 * </pre>
 *
 * <p>⚠️ 필드가 구글·깃허브처럼 최상위에 있지 않고 {@code kakao_account} 아래에 있다. 그리고 <b>동의하지 않은 항목은
 * 키 자체가 빠진다</b> — 이메일 동의를 거부했거나 앱에 이메일 동의항목이 설정돼 있지 않으면 {@code email} 이 없다.
 * 그때는 null 을 돌려주고, {@code CustomOAuth2UserService} 가 {@code email_required} 로 거절한다.
 *
 * <p>검증 여부({@link #isEmailVerified()})는 파서가 판정하지 않고 드러내기만 한다 — 거절은 서비스가 한다(구글과 같은 자리).
 */
public class KakaoResponse implements OAuth2Response {

    private final Map<String, Object> attribute;
    private final Map<String, Object> account;

    public KakaoResponse(Map<String, Object> attribute) {
        this.attribute = attribute;
        this.account = asMap(attribute.get("kakao_account"));
    }

    @Override
    public String getProvider() {
        return "kakao";
    }

    @Override
    public String getProviderId() {
        return String.valueOf(attribute.get("id"));
    }

    @Override
    public String getEmail() {
        Object email = account.get("email");
        return email != null && !email.toString().isBlank() ? email.toString() : null;
    }

    /**
     * 카카오가 이 주소를 "유효하고 인증된" 것으로 보는가. 둘 다 true 여야 한다 — {@code is_email_valid=false} 는 다른 계정에
     * 넘어간(재사용된) 주소, {@code is_email_verified=false} 는 소유 확인을 하지 않은 주소다. 어느 쪽이든 그 주소로 기존
     * 계정에 연결하면 남의 계정을 가져갈 수 있다.
     */
    public boolean isEmailVerified() {
        return Boolean.TRUE.equals(account.get("is_email_valid"))
                && Boolean.TRUE.equals(account.get("is_email_verified"));
    }

    @Override
    public String getName() {
        // 닉네임 동의는 선택일 수 있다 — kakao_account.profile → properties → 이메일 앞부분 순으로.
        Object nickname = asMap(account.get("profile")).get("nickname");
        if (nickname == null) {
            nickname = asMap(attribute.get("properties")).get("nickname");
        }
        if (nickname != null && !nickname.toString().isBlank()) {
            return nickname.toString();
        }
        String email = getEmail();
        return email != null ? email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length())
                : "kakao_" + getProviderId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
