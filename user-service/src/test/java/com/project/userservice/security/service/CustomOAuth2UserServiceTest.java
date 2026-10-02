package com.project.userservice.security.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.project.userservice.constants.AppRole;
import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;

import java.time.Instant;
import java.util.Set;

/**
 * 소셜 사용자 적재 회귀 테스트.
 *
 * <p>예전 구현의 실패 방식이 특이해 그대로 고정한다: {@code findByUsername(...).orElseThrow}
 * 뒤에 {@code if (existData == null)} 이 오는 죽은 분기라 <b>신규 사용자는 예외로,
 * 기존 사용자는 이메일 덮어쓰기로</b> 끝났고, 성공 핸들러는 "google 12345" 를 이메일로
 * 조회해 모든 소셜 로그인이 user_not_found 로 끝났다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomOAuth2UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SocialAccountLinker socialAccountLinker;

    @org.junit.jupiter.api.BeforeEach
    void passThroughLinker() {
        // 연결 규칙은 SocialAccountLinkerTest 가 본다 — 여기서는 받은 계정을 그대로 돌려준다.
        when(socialAccountLinker.link(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Spy
    @InjectMocks
    private CustomOAuth2UserService service;

    private OAuth2UserRequest request(String registrationId) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("id").clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com/cb")
                .authorizationUri("https://example.com/a")
                .tokenUri("https://example.com/t")
                .userInfoUri("https://example.com/u")
                .userNameAttributeName("id")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "tok", Instant.now(), Instant.now().plusSeconds(600));
        return new OAuth2UserRequest(registration, token);
    }

    private void stubProviderUser(Map<String, Object> attributes, String nameAttr) {
        OAuth2User providerUser = new DefaultOAuth2User(Set.of(), attributes, nameAttr);
        doReturn(providerUser).when(service).loadUserFromProvider(any());
    }

    private void givenRoleExists() {
        when(roleRepository.findByRoleName(AppRole.ROLE_USER))
                .thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("처음 온 구글 사용자는 ROLE_USER 로 생성된다 — 역할은 DB 에서 찾는다(새 행을 만들지 않는다)")
    void createsNewGoogleUser() {
        givenRoleExists();
        when(userRepository.findByEmail("new@gmail.com")).thenReturn(Optional.empty());
        stubProviderUser(Map.of("sub", "g-1", "email", "new@gmail.com",
                "email_verified", "true", "name", "새 사용자"), "sub");

        CustomOAuth2User principal = (CustomOAuth2User) service.loadUser(request("google"));

        assertEquals("new@gmail.com", principal.getEmail());
        verify(userRepository).save(argThat(u -> "google".equals(u.getSignUpMethod())
                && u.isEnabled()
                && "google_g-1".equals(u.getUsername())));
    }

    @Test
    @DisplayName("같은 이메일의 기존 계정이 있으면 새로 만들지 않고 연결한다")
    void linksExistingAccountByEmail() {
        User existing = new User("kim", "kim@example.com", "$2a$pw");
        existing.setRole(new Role(AppRole.ROLE_ADMIN));
        when(userRepository.findByEmail("kim@example.com")).thenReturn(Optional.of(existing));
        stubProviderUser(Map.of("sub", "g-2", "email", "kim@example.com",
                "email_verified", "true", "name", "Kim"), "sub");

        CustomOAuth2User principal = (CustomOAuth2User) service.loadUser(request("google"));

        // 기존 계정의 역할이 그대로 온다 — 관리자가 소셜로 들어와도 관리자다.
        assertTrue(principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(userRepository, never()).save(any());
        // 🔒 기존 계정이면 반드시 연결 규칙(사전 탈취 방어)을 지난다(docs/68 §15).
        verify(socialAccountLinker).link(existing, "google");
    }

    @Test
    @DisplayName("검증 안 된 구글 이메일은 거절 — 미검증 주소를 믿으면 계정 탈취 경로가 된다")
    void rejectsUnverifiedGoogleEmail() {
        stubProviderUser(Map.of("sub", "g-3", "email", "victim@example.com",
                "email_verified", "false"), "sub");

        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request("google")));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("깃허브 이메일이 비공개면 /user/emails 폴백으로 찾는다")
    void githubEmailFallback() {
        givenRoleExists();
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.empty());
        // GithubResponse: email null → 폴백 호출
        stubProviderUser(Map.of("id", 777, "login", "devkim"), "id");
        doReturn("dev@example.com").when(service).fetchGithubPrimaryEmail("tok");

        CustomOAuth2User principal = (CustomOAuth2User) service.loadUser(request("github"));

        assertEquals("dev@example.com", principal.getEmail());
        verify(userRepository).save(argThat(u -> "github_777".equals(u.getUsername())));
    }

    @Test
    @DisplayName("폴백까지 실패해 이메일이 없으면 거절 — 이 저장소의 사용자 식별자는 이메일이다")
    void rejectsWhenNoEmailAtAll() {
        stubProviderUser(Map.of("id", 888, "login", "noemail"), "id");
        doReturn(null).when(service).fetchGithubPrimaryEmail(any());

        OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
                () -> service.loadUser(request("github")));
        assertEquals("email_required", e.getError().getErrorCode());
    }

    @Test
    @DisplayName("페이스북 사용자도 생성된다")
    void createsFacebookUser() {
        givenRoleExists();
        when(userRepository.findByEmail("fb@example.com")).thenReturn(Optional.empty());
        stubProviderUser(Map.of("id", "fb-9", "email", "fb@example.com", "name", "FB User"), "id");

        CustomOAuth2User principal = (CustomOAuth2User) service.loadUser(request("facebook"));

        assertEquals("fb@example.com", principal.getEmail());
        verify(userRepository).save(argThat(u -> "facebook".equals(u.getSignUpMethod())));
    }

    private static Map<String, Object> kakao(long id, Map<String, Object> account) {
        return Map.of("id", id, "kakao_account", account);
    }

    @Test
    @DisplayName("카카오 — 이메일은 kakao_account 아래에 있다. 처음 온 사용자는 kakao_<id> 로 생성된다")
    void createsKakaoUser() {
        givenRoleExists();
        when(userRepository.findByEmail("kim@kakao.com")).thenReturn(Optional.empty());
        stubProviderUser(kakao(4242L, Map.of("email", "kim@kakao.com", "is_email_valid", true,
                "is_email_verified", true, "profile", Map.of("nickname", "김카카오"))), "id");

        CustomOAuth2User principal = (CustomOAuth2User) service.loadUser(request("kakao"));

        assertEquals("kim@kakao.com", principal.getEmail());
        verify(userRepository).save(argThat(u -> "kakao".equals(u.getSignUpMethod())
                && "kakao_4242".equals(u.getUsername())
                && "김카카오".equals(u.getName())));
    }

    @Test
    @DisplayName("🔴 카카오 — 인증되지 않은(또는 재사용된) 이메일로는 기존 계정에 연결하지 않는다")
    void rejectsUnverifiedKakaoEmail() {
        stubProviderUser(kakao(1L, Map.of("email", "victim@example.com", "is_email_valid", true,
                "is_email_verified", false)), "id");

        OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
                () -> service.loadUser(request("kakao")));
        assertEquals("unverified_email", e.getError().getErrorCode());

        stubProviderUser(kakao(2L, Map.of("email", "victim@example.com", "is_email_valid", false,
                "is_email_verified", true)), "id");
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request("kakao")));

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("카카오 — 이메일 동의를 안 하면(키 자체가 없다) email_required 로 거절한다")
    void rejectsKakaoWithoutEmailConsent() {
        stubProviderUser(kakao(3L, Map.of("profile", Map.of("nickname", "익명"))), "id");

        OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
                () -> service.loadUser(request("kakao")));
        assertEquals("email_required", e.getError().getErrorCode());
    }

    @Test
    @DisplayName("LINE — 클레임의 sub·email·name 으로 만든다(line_<sub>)")
    void createsLineUserFromClaims() {
        givenRoleExists();
        when(userRepository.findByEmail("taro@example.jp")).thenReturn(Optional.empty());

        CustomOAuth2User principal = service.resolve("line",
                Map.of("sub", "U4af49806", "email", "taro@example.jp", "name", "山田太郎"), "tok");

        assertEquals("taro@example.jp", principal.getEmail());
        verify(userRepository).save(argThat(u -> "line".equals(u.getSignUpMethod())
                && "line_U4af49806".equals(u.getUsername()) && "山田太郎".equals(u.getName())));
    }

    @Test
    @DisplayName("LINE — 이메일 권한이 없으면(email 클레임 없음) email_required")
    void rejectsLineWithoutEmail() {
        OAuth2AuthenticationException e = assertThrows(OAuth2AuthenticationException.class,
                () -> service.resolve("line", Map.of("sub", "U1", "name", "x"), "tok"));
        assertEquals("email_required", e.getError().getErrorCode());
    }
}
