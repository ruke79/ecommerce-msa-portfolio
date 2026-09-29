package com.project.userservice.security.oauth2;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/** 다른 패키지의 테스트가 실제 등록 모양을 쓰게 한다. */
public final class SocialClientRegistrationTestSupport {

    private SocialClientRegistrationTestSupport() {
    }

    public static ClientRegistration line() {
        return SocialClientRegistration.line("id", "secret", "https://example.com/user-service/login/oauth2/code/line");
    }

    public static ClientRegistration google() {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("id").clientSecret("secret").build();
    }
}
