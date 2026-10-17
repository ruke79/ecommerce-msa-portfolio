package com.project.userservice.security.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.project.userservice.constants.AppRole;
import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.response.FacebookResponse;
import com.project.userservice.security.response.GithubResponse;
import com.project.userservice.security.response.GoogleResponse;
import com.project.userservice.security.response.KakaoResponse;
import com.project.userservice.security.response.LineResponse;
import com.project.userservice.security.response.OAuth2Response;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 소셜 로그인 사용자 적재 — 프로바이더 응답을 이 저장소의 User 로 잇는다.
 *
 * <p>예전 버전은 튜토리얼을 옮기다 만 상태였다: naver/google 만 알고,
 * {@code findByUsername(...).orElseThrow(...)} 뒤에 {@code if (existData == null)} 이
 * 오는 <b>죽은 분기</b>(orElseThrow 가 던지므로 절대 null 이 아니다)에, 새 사용자는
 * {@code new Role(...)} 로 역할 행을 **또 만들었고**, enabled·만료일 같은 계정 초기화는
 * 전부 빠져 있었다.
 *
 * <p>연결 규칙: <b>검증된 이메일</b>로 기존 계정을 찾는다. 같은 이메일의 로컬 계정이
 * 있으면 그 계정으로 로그인된다(계정 연결). 이메일 검증을 요구하는 이유 — 프로바이더가
 * 검증하지 않은 주소를 믿으면, 공격자가 남의 이메일로 소셜 계정을 만들어 그 사람의
 * 로컬 계정을 탈취할 수 있다. 구글은 {@code email_verified} 클레임을, 깃허브는
 * {@code /user/emails} 의 {@code verified} 플래그를, 카카오는 {@code is_email_valid}·
 * {@code is_email_verified} 를 확인한다(페이스북·LINE 은 검증된 주소만 내려준다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    /** 기존 계정에 연결할 때의 사전 탈취 방어 · 연결 알림(docs/68 §15). */
    private final SocialAccountLinker socialAccountLinker;

    /** 깃허브 폴백 호출의 상한 — 로그인 스레드가 프로바이더에 묶이지 않게. */
    static final Duration GITHUB_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 깃허브 이메일 폴백용. 테스트에서 바꿔 끼울 수 있게 필드로 둔다.
     *
     * <p>🐛 {@code new RestTemplate()} 의 기본 팩토리는 타임아웃이 <b>무제한</b>이다 — 깃허브가 응답하지 않으면 그 로그인
     * 요청의 스레드가 끝없이 묶인다(톰캣 스레드 풀이 마르면 다른 요청도 함께 멈춘다). 이메일 폴백은 없으면 거절하면 그만이므로
     * 오래 기다릴 이유가 없다.
     */
    // [JA] GitHub メールのフォールバック用。既定の RestTemplate はタイムアウトが無制限で、応答がないとログインのスレッドが縛られる。
    RestTemplate rest = restWithTimeout();

    private static RestTemplate restWithTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) GITHUB_TIMEOUT.toMillis());
        factory.setReadTimeout((int) GITHUB_TIMEOUT.toMillis());
        return new RestTemplate(factory);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = loadUserFromProvider(userRequest);
        return resolve(userRequest.getClientRegistration().getRegistrationId(), oAuth2User.getAttributes(),
                userRequest.getAccessToken().getTokenValue());
    }

    /**
     * 프로바이더가 준 속성으로 이 저장소의 사용자를 찾거나 만든다 — OAuth2 경로(loadUser)와 OIDC 경로
     * ({@link CustomOidcUserService}, 구글·LINE)가 <b>같은 규칙</b>을 쓰게 하는 한 곳이다.
     *
     * @param attributes OAuth2 는 userinfo 응답, OIDC 는 ID 토큰과 userinfo 를 합친 클레임
     */
    public CustomOAuth2User resolve(String registrationId, Map<String, Object> attributes, String accessToken) {
        OAuth2Response response = switch (registrationId) {
            case "google" -> {
                // 검증 안 된 구글 계정은 거의 없지만, 클레임이 있는데 확인하지 않는 것은
                // 계정 연결 규칙(클래스 주석)에 대한 태만이다.
                Object verified = attributes.get("email_verified");
                if (verified != null && !Boolean.parseBoolean(verified.toString())) {
                    throw error("unverified_email", "구글 계정의 이메일이 검증되지 않았습니다.");
                }
                yield new GoogleResponse(attributes);
            }
            case "github" -> new GithubResponse(attributes);
            case "facebook" -> new FacebookResponse(attributes);
            // LINE 은 OIDC 로만 온다 — 이메일은 ID 토큰의 email 클레임(채널에 이메일 권한 승인이 있어야 실린다).
            case "line" -> new LineResponse(attributes);
            case "kakao" -> {
                KakaoResponse kakao = new KakaoResponse(attributes);
                // 이메일이 없으면 아래의 email_required 로 떨어진다. 있는데 검증되지 않았으면 여기서 거절한다.
                if (kakao.getEmail() != null && !kakao.isEmailVerified()) {
                    throw error("unverified_email", "카카오 계정의 이메일이 인증되지 않았습니다.");
                }
                yield kakao;
            }
            default -> throw error("unsupported_provider", "지원하지 않는 프로바이더: " + registrationId);
        };

        String email = response.getEmail();
        if (email == null && "github".equals(registrationId)) {
            email = fetchGithubPrimaryEmail(accessToken);
        }
        if (email == null || email.isBlank()) {
            // 이 저장소의 사용자 식별자는 이메일이다 — 없으면 만들 수도 연결할 수도 없다.
            throw error("email_required",
                    "프로바이더가 이메일을 주지 않았습니다. 계정의 이메일 공개 설정을 확인하세요.");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = createSocialUser(response, email, registrationId);
        } else {
            log.info("소셜 로그인 → 기존 계정 연결. provider={}, userId={}", registrationId, user.getUserId());
            // 🔒 주소가 확인된 적 없는 비밀번호 계정이면 그 비밀번호를 해제한다 — 남의 주소로 먼저 가입해 둔 사람이 들어오지 못하게.
            user = socialAccountLinker.link(user, registrationId);
        }

        return new CustomOAuth2User(new ResolvedResponse(response, email),
                user.getRole().getRoleName().name());
    }

    /**
     * 프로바이더의 userinfo 를 실제로 가져오는 부분 — 테스트가 갈아 끼우는 이음새다.
     * {@code super.loadUser} 는 실 HTTP 호출이라 스텁할 수 없다.
     */
    OAuth2User loadUserFromProvider(OAuth2UserRequest userRequest) {
        return super.loadUser(userRequest);
    }

    /** users.username · users.name 의 길이(varchar(20), V2). */
    static final int USERNAME_MAX = 20;

    /**
     * 소셜 사용자의 username — {@code provider_providerId}, 20자를 넘으면 안정된 짧은 꼴.
     *
     * <p>🐛 예전: 그대로 썼다. 구글 sub 는 21자리라 {@code google_…} 가 28자 — users.username(varchar(20))에 들어가지 않아
     * 구글 · 페이스북 · 네이버 · LINE 가입이 DB 에서 거절됐다(깃허브 · 카카오는 짧아서 통했다). 컬럼을 넓히지 않고 맞춘다:
     * 이미 맞는 것(기존 계정)은 그대로, 넘는 것만 {@code 프로바이더앞6자_SHA-256 앞 64비트의 36진수} — 같은 계정이면 늘 같은 값이고,
     * 기존 계정은 이메일로 찾으므로 이름 규칙이 바뀌어도 영향이 없다.
     */
    // [JA] ソーシャルユーザーの username — 20 文字を超えたら安定した短い形に。以前は google_ + 21 桁の sub で 28 文字になり、
    //      varchar(20) に入らず Google · Facebook · Naver · LINE の登録が DB で拒否されていた。
    static String socialUsername(String provider, String providerId) {
        String raw = provider + "_" + providerId;
        if (raw.length() <= USERNAME_MAX) {
            return raw;
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long bits = java.nio.ByteBuffer.wrap(h, 0, 8).getLong();
            String prefix = provider.length() > 6 ? provider.substring(0, 6) : provider;
            return prefix + "_" + Long.toUnsignedString(bits, 36);   // 최대 6 + 1 + 13 = 20자
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 모든 JVM 에 있다
        }
    }

    /** 표시 이름 — 컬럼(20자)에 맞게 자른다. 서로게이트 쌍(이모지)을 반으로 자르지 않는다. */
    static String cap(String value, int max) {
        if (value == null || value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private User createSocialUser(OAuth2Response response, String email, String provider) {
        Role role = roleRepository.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException(
                        "users.roles 에 ROLE_USER 가 없습니다 — V3__seed_roles.sql 확인"));

        // username 은 유일해야 한다. 소셜 사용자는 provider_providerId — 사람이 고른
        // 이름과 절대 충돌하지 않는 형태다. 20자를 넘으면 줄인다(socialUsername).
        User user = new User(socialUsername(response.getProvider(), response.getProviderId()), email,
                // 비밀번호 로그인 경로를 확실히 잠근다. null 로 두면 BCrypt matches 가
                // 예외를 던지는 애매한 상태가 된다 — 아무도 모르는 난수를 넣는다.
                passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setName(cap(response.getName(), USERNAME_MAX));
        user.setRole(role);
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        // 프로바이더가 이메일을 검증했으므로 메일 인증 게이트를 다시 세우지 않는다.
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        user.setAccountExpiryDate(LocalDate.now().plusYears(1));
        user.setTwoFactorEnabled(false);
        user.setSignUpMethod(provider);

        log.info("소셜 가입. provider={}", provider);
        return userRepository.save(user);
    }

    /**
     * 깃허브 프로필 이메일이 비공개인 계정의 폴백 — {@code /user/emails} 에서
     * primary+verified 주소를 찾는다(user:email scope 로 받은 토큰이 필요하다).
     */
    String fetchGithubPrimaryEmail(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            List<Map<String, Object>> emails = rest.exchange(
                    RequestEntity.get("https://api.github.com/user/emails").headers(headers).build(),
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    }).getBody();
            if (emails == null) {
                return null;
            }
            return emails.stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("verified")))
                    .sorted((a, b) -> Boolean.compare(
                            Boolean.TRUE.equals(b.get("primary")), Boolean.TRUE.equals(a.get("primary"))))
                    .map(e -> String.valueOf(e.get("email")))
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            // 🔒 예외 원문은 남기지 않는다 — 깃허브 응답 본문 · 요청 URL 에 이메일 주소가 섞여 로그(Loki)에 남는다(//    notification 의 N-H2 와 같은 결함). 종류만으로 충분하다 — 어차피 폴백 실패는 email_required 로 끝난다.
            log.warn("깃허브 이메일 조회 실패 — {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private OAuth2AuthenticationException error(String code, String message) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, message, null), message);
    }

    /**
     * 깃허브 폴백으로 얻은 이메일을 응답에 덧입힌다 — 원본 파서는 null 을 돌려줬지만
     * 이후 로직(성공 핸들러의 사용자 조회)은 확정된 이메일이 필요하다.
     */
    private record ResolvedResponse(OAuth2Response delegate, String email) implements OAuth2Response {
        @Override
        public String getProvider() {
            return delegate.getProvider();
        }

        @Override
        public String getProviderId() {
            return delegate.getProviderId();
        }

        @Override
        public String getEmail() {
            return email;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }
    }
}
