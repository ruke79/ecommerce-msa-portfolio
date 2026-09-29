package com.project.userservice.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

import com.project.userservice.security.oauth2.SocialClientRegistrationTestSupport;

@DisplayName("ID 토큰 서명 알고리즘 — 등록마다")
class IdTokenAlgorithmTest {

    @Test
    @DisplayName("🔴 LINE 웹 로그인의 ID 토큰은 ES256 — 스프링 기본(RS256)이면 모든 LINE 로그인이 invalid_id_token")
    void lineIsEs256() {
        assertEquals(SignatureAlgorithm.ES256,
                SecurityConfig.idTokenAlgorithm(SocialClientRegistrationTestSupport.line()));
        assertEquals(SignatureAlgorithm.RS256,
                SecurityConfig.idTokenAlgorithm(SocialClientRegistrationTestSupport.google()));
    }
}
