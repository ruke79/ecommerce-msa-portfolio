package com.project.userservice.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("소셜 로그인 실패 — 이유 코드를 들고 로그인 화면으로")
class OAuth2FailureHandlerTest {

    private final OAuth2FailureHandler handler = new OAuth2FailureHandler();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "frontendUrl", "https://www.example.com");
    }

    private String redirectFor(Exception e) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), res,
                (org.springframework.security.core.AuthenticationException) e);
        return res.getRedirectedUrl();
    }

    @Test
    @DisplayName("화면이 안내하는 코드는 그대로 — 이메일 동의 누락·미인증 이메일·동의 취소")
    void shownCodesPassThrough() throws Exception {
        for (String code : new String[] { "email_required", "unverified_email", "access_denied" }) {
            assertEquals("https://www.example.com/signin?error=" + code,
                    redirectFor(new OAuth2AuthenticationException(new OAuth2Error(code, "설명", null))));
        }
    }

    @Test
    @DisplayName("🔴 그 밖의 코드·설명은 URL 에 싣지 않는다 — oauth_failed 하나로")
    void otherCodesAreGeneric() throws Exception {
        String url = redirectFor(new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token_response", "token endpoint said: secret=abc", null)));
        assertEquals("https://www.example.com/signin?error=oauth_failed", url);
        assertEquals("https://www.example.com/signin?error=oauth_failed",
                redirectFor(new BadCredentialsException("x")));
    }
}
