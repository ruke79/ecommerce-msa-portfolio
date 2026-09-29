package com.project.userservice.security.response;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("카카오 사용자 정보 파싱")
class KakaoResponseTest {

    @Test
    @DisplayName("id 는 숫자다 — 문자열로 돌려준다")
    void providerIdIsNumeric() {
        KakaoResponse r = new KakaoResponse(Map.of("id", 1234567890123L));
        assertEquals("1234567890123", r.getProviderId());
        assertEquals("kakao", r.getProvider());
    }

    @Test
    @DisplayName("동의하지 않은 항목은 키가 없다 — kakao_account 가 없어도 NPE 없이 null")
    void missingAccountIsNull() {
        KakaoResponse r = new KakaoResponse(Map.of("id", 1L));
        assertNull(r.getEmail());
        assertFalse(r.isEmailVerified());
        assertEquals("kakao_1", r.getName());
    }

    @Test
    @DisplayName("이름: profile.nickname → properties.nickname → 이메일 앞부분")
    void nameFallbacks() {
        assertEquals("프로필", new KakaoResponse(Map.of("id", 1L,
                "kakao_account", Map.of("profile", Map.of("nickname", "프로필")),
                "properties", Map.of("nickname", "속성"))).getName());
        assertEquals("속성", new KakaoResponse(Map.of("id", 1L,
                "properties", Map.of("nickname", "속성"))).getName());
        assertEquals("kim", new KakaoResponse(Map.of("id", 1L,
                "kakao_account", Map.of("email", "kim@kakao.com"))).getName());
    }

    @Test
    @DisplayName("검증은 is_email_valid · is_email_verified 둘 다 true 일 때만")
    void verificationNeedsBothFlags() {
        assertTrue(new KakaoResponse(Map.of("id", 1L, "kakao_account",
                Map.of("email", "a@b.c", "is_email_valid", true, "is_email_verified", true))).isEmailVerified());
        assertFalse(new KakaoResponse(Map.of("id", 1L, "kakao_account",
                Map.of("email", "a@b.c", "is_email_valid", true))).isEmailVerified());
    }
}
