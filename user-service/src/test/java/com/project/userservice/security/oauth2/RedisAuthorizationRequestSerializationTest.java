package com.project.userservice.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.project.userservice.security.oauth2.repository.RedisOAuth2AuthorizationRequestRepository;

/**
 * 인가 요청의 <b>직렬화 왕복</b> — Redis 없이, 진짜 저장/복원 코드 경로로.
 *
 * <p>여기가 이 변경에서 가장 조용히 깨질 수 있는 곳이다. {@link OAuth2AuthorizationRequest}
 * 는 평범한 DTO 가 아니라 스프링 시큐리티의 Jackson 믹스인이 있어야 복원되고, 그 믹스인은
 * 다형 타입 정보(@class)를 켠 매퍼를 전제한다. 설정이 하나라도 빠지면 <b>저장은 되는데
 * 복원이 안 되는</b> 형태로 나타나고, 사용자에게는 "로그인이 그냥 안 돼요"로만 보인다.
 *
 * <p>특히 {@code code_verifier} 가 살아남는지를 본다. 그 값이 유실되면 프로바이더는
 * 인가까지 정상 처리한 뒤 <b>토큰 교환에서만</b> 거부하므로, 증상이 나타나는 지점과
 * 원인이 있는 지점이 멀어진다.
 *
 * <p>Redis 자체의 동작(GETDEL·TTL·파드 간 공유)은 컨테이너가 필요하므로
 * {@code RedisOAuth2AuthorizationRequestRepositoryTest} 가 맡는다.
 */
@DisplayName("인가 요청 직렬화 왕복")
class RedisAuthorizationRequestSerializationTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisOAuth2AuthorizationRequestRepository repository;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        repository = new RedisOAuth2AuthorizationRequestRepository(redis, 600);
    }

    /** 운영에서 실제로 만들어지는 것과 같은 모양 — PKCE 커스터마이저를 통과시킨다. */
    private static OAuth2AuthorizationRequest pkceRequest() {
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://shop.example.com/user-service/login/oauth2/code/google")
                .scopes(java.util.Set.of("openid", "profile", "email"))
                .state("test-state-abc123");

        // 실제 리졸버가 쓰는 그 커스터마이저를 그대로 통과시킨다 — 손으로 흉내 내면
        // 스프링이 형식을 바꿨을 때 테스트만 통과하고 운영이 깨진다.
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("registration_id", "google");
        return builder.attributes(a -> a.putAll(attrs)).build();
    }

    private OAuth2AuthorizationRequest saveThenLoad(OAuth2AuthorizationRequest original) {
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("oauth2:authreq:" + original.getState()), json.capture(),
                any(Duration.class));

        // 저장된 그 문자열을 그대로 되돌려준다 — Redis 가 하는 일이 정확히 이것이다.
        when(valueOps.get(anyString())).thenReturn(json.getValue());

        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setParameter("state", original.getState());
        return repository.loadAuthorizationRequest(callback);
    }

    @Test
    @DisplayName("code_verifier 가 왕복에서 살아남는다 — 유실되면 토큰 교환에서만 터진다")
    void codeVerifier_survivesRoundTrip() {
        OAuth2AuthorizationRequest original = pkceRequest();
        String verifier = (String) original.getAttributes().get("code_verifier");
        assertThat(verifier).as("전제: 원본에 verifier 가 있어야 한다").isNotBlank();

        OAuth2AuthorizationRequest restored = saveThenLoad(original);

        assertThat(restored).isNotNull();
        assertThat(restored.getAttributes().get("code_verifier")).isEqualTo(verifier);
    }

    @Test
    @DisplayName("state·redirectUri·scope·additionalParameters 도 그대로 돌아온다")
    void allFields_surviveRoundTrip() {
        OAuth2AuthorizationRequest original = pkceRequest();
        OAuth2AuthorizationRequest restored = saveThenLoad(original);

        assertThat(restored.getState()).isEqualTo(original.getState());
        assertThat(restored.getClientId()).isEqualTo(original.getClientId());
        assertThat(restored.getRedirectUri()).isEqualTo(original.getRedirectUri());
        assertThat(restored.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
        assertThat(restored.getScopes()).isEqualTo(original.getScopes());
        assertThat(restored.getGrantType()).isEqualTo(original.getGrantType());

        // 프로바이더로 나가는 PKCE 파라미터.
        assertThat(restored.getAdditionalParameters().get("code_challenge"))
                .isEqualTo(original.getAdditionalParameters().get("code_challenge"));
        assertThat(restored.getAdditionalParameters().get("code_challenge_method")).isEqualTo("S256");

        // 콜백에서 어느 프로바이더였는지 알아내는 데 쓰인다 — 빠지면 콜백이 깨진다.
        assertThat(restored.getAttributes().get("registration_id")).isEqualTo("google");
    }

    @Test
    @DisplayName("state 파라미터가 없는 콜백은 조회를 시도하지 않는다")
    void noStateParameter_doesNotHitRedis() {
        // state 없는 콜백은 위조이거나 잘못 연결된 요청이다. Redis 에 물어볼 이유가 없고,
        // null 키로 조회하면 그쪽에서 NPE 가 난다.
        assertThat(repository.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("null 저장은 삭제로 해석된다 — 계약대로")
    void savingNull_removesInstead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "test-state-abc123");

        repository.saveAuthorizationRequest(null, request, new MockHttpServletResponse());

        verify(valueOps).getAndDelete("oauth2:authreq:test-state-abc123");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("state 없는 인가 요청은 저장을 거부한다 — CSRF 방어가 없다는 뜻이므로")
    void requestWithoutState_isRejected() {
        OAuth2AuthorizationRequest noState = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://shop.example.com/cb")
                .build();

        assertThatThrownBy(() -> repository.saveAuthorizationRequest(noState,
                new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("깨진 값은 null 로 처리하고 그 항목을 지운다 — 같은 오류를 반복하지 않게")
    void corruptValue_isDiscarded() {
        when(valueOps.get(anyString())).thenReturn("{이건 JSON 이 아니다");

        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setParameter("state", "test-state-abc123");

        assertThat(repository.loadAuthorizationRequest(callback)).isNull();
        verify(redis).delete("oauth2:authreq:test-state-abc123");
    }
}
