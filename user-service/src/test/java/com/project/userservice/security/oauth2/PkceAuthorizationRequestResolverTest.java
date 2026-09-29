package com.project.userservice.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * PKCE 가 실제로 인가 요청에 붙는지 — 그리고 <b>커스터마이저 없이는 붙지 않는다는 것</b>.
 *
 * <p>두 번째가 이 테스트의 핵심이다. 스프링의 기본 리졸버는 PKCE 를 <b>공개 클라이언트에만</b>
 * 자동 적용한다({@code DefaultOAuth2AuthorizationRequestResolver} 가
 * {@code ClientAuthenticationMethod.NONE} 일 때만 붙인다). 우리 프로바이더는 셋 다 클라이언트
 * 시크릿을 쓰는 기밀 클라이언트라 그 분기에 걸리지 않았고, 그래서 <b>PKCE 가 전혀 걸려 있지
 * 않았다.</b> "oauth2Login 을 썼으니 요즘 기본은 PKCE 겠지"라고 넘기기 딱 좋은 지점이다.
 *
 * <p>그래서 여기서는 켠 쪽과 끈 쪽을 나란히 검사한다. 나중에 누군가
 * {@code SecurityConfig.pkceAuthorizationRequestResolver()} 를 지우면 두 번째 테스트가 아니라
 * <b>첫 번째 테스트가 깨져서</b> 그 사실을 알려 준다.
 */
@DisplayName("PKCE 인가 요청 리졸버")
class PkceAuthorizationRequestResolverTest {

    /** 운영과 같은 방식으로 만든 등록 — SocialClientRegistration 이 하는 것과 동일하다. */
    private static ClientRegistration googleRegistration() {
        return CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .redirectUri("https://shop.example.com/user-service/login/oauth2/code/google")
                .build();
    }

    private static MockHttpServletRequest authorizationRequestFor(String registrationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/oauth2/authorization/" + registrationId);
        request.setServletPath("/oauth2/authorization/" + registrationId);
        return request;
    }

    private static OAuth2AuthorizationRequest resolve(boolean withPkce) {
        ClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(googleRegistration());
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                repo, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        if (withPkce) {
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        }
        return resolver.resolve(authorizationRequestFor("google"));
    }

    @Test
    @DisplayName("커스터마이저를 걸면 code_challenge(S256)와 code_verifier 가 생긴다")
    void pkce_isApplied_whenCustomizerIsSet() {
        OAuth2AuthorizationRequest req = resolve(true);

        assertThat(req).isNotNull();

        // 프로바이더로 나가는 값 — 검증자의 해시.
        assertThat(req.getAdditionalParameters()).containsKey("code_challenge");
        assertThat(req.getAdditionalParameters().get("code_challenge_method")).isEqualTo("S256");

        // 서버에 남아야 하는 비밀 — 토큰 교환 때 이 값을 함께 보낸다.
        // ⚠️ 이것이 attributes 에 들어간다는 사실이 인가 요청 저장소 선택을 좌우한다.
        //    쿠키에 담으면 브라우저로 나가고, 그러면 PKCE 의 전제가 무너진다.
        assertThat(req.getAttributes()).containsKey("code_verifier");
        assertThat((String) req.getAttributes().get("code_verifier")).isNotBlank();

        // 인가 URI 에도 실제로 실려 나가는지 — 객체에만 있고 요청에 안 실리면 의미가 없다.
        assertThat(req.getAuthorizationRequestUri()).contains("code_challenge=");
        assertThat(req.getAuthorizationRequestUri()).contains("code_challenge_method=S256");
    }

    @Test
    @DisplayName("기밀 클라이언트는 커스터마이저가 없으면 PKCE 가 붙지 않는다 — 개편 전 상태")
    void pkce_isAbsent_forConfidentialClient_byDefault() {
        // 전제 확인: 이 등록은 기밀 클라이언트다(그래서 기본 PKCE 대상이 아니다).
        assertThat(googleRegistration().getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);

        OAuth2AuthorizationRequest req = resolve(false);

        assertThat(req).isNotNull();
        assertThat(req.getAdditionalParameters()).doesNotContainKey("code_challenge");
        assertThat(req.getAttributes()).doesNotContainKey("code_verifier");
        assertThat(req.getAuthorizationRequestUri()).doesNotContain("code_challenge");
    }

    @Test
    @DisplayName("state 는 매 요청 새로 생기고 예측할 수 없다")
    void state_isFreshEveryTime() {
        // Redis 저장소가 state 를 키로 쓴다. 값이 재사용되면 서로 다른 로그인 시도가
        // 같은 키를 덮어쓰게 되므로, 키로 삼아도 되는 성질인지 여기서 고정한다.
        String first = resolve(true).getState();
        String second = resolve(true).getState();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
        assertThat(first.length()).isGreaterThanOrEqualTo(12);
    }
}
