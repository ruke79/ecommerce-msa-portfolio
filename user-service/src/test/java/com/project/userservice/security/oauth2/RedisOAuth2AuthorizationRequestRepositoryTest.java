package com.project.userservice.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.project.userservice.security.oauth2.repository.RedisOAuth2AuthorizationRequestRepository;

/**
 * 실제 Redis 위에서의 인가 요청 저장소 — <b>파드가 둘일 때</b>도 로그인이 되는지.
 *
 * <p>이 테스트가 존재하는 이유는 단 하나다. 기본 구현은 인가 요청을 HTTP 세션에 넣는데,
 * 이 서비스는 레플리카가 2개이고(HPA minReplicas=2) 세션 복제도 어피니티도 없다. 그래서
 * <b>로그인을 시작한 파드와 콜백을 받은 파드가 다르면 로그인이 깨진다.</b> 대략 절반의
 * 확률로 실패하는데, 재시도하면 되는 경우가 있어 "가끔 안 되네" 로만 보인다 — 가장
 * 진단하기 나쁜 종류의 고장이다.
 *
 * <p>아래 {@code 서로 다른 인스턴스}(= 서로 다른 파드) 테스트가 그 상황을 그대로 만든다.
 *
 * <p>직렬화 자체는 컨테이너 없이 {@code RedisAuthorizationRequestSerializationTest} 가
 * 검사한다. 여기서는 <b>Redis 가 실제로 하는 일</b>(GETDEL 의 원자성, TTL, 인스턴스 간
 * 공유)만 본다.
 */
@Tag("containers")
@Testcontainers
@DisplayName("인가 요청 저장소 (실제 Redis)")
class RedisOAuth2AuthorizationRequestRepositoryTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "testpw");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        config.setPassword("testpw");
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static RedisOAuth2AuthorizationRequestRepository newRepository(long ttlSeconds) {
        return new RedisOAuth2AuthorizationRequestRepository(template, ttlSeconds);
    }

    private static OAuth2AuthorizationRequest requestWithState(String state) {
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://shop.example.com/user-service/login/oauth2/code/google")
                .scopes(java.util.Set.of("openid", "email"))
                .state(state);
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        return builder.attributes(a -> a.put("registration_id", "google")).build();
    }

    private static MockHttpServletRequest callbackWith(String state) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", state);
        return request;
    }

    @Test
    @DisplayName("한 파드에서 시작한 로그인을 다른 파드가 이어받는다 — 이 변경의 목적")
    void authorizationRequest_isSharedAcrossInstances() {
        // 인스턴스 두 개 = 파드 두 개. 공유하는 것은 Redis 뿐이다(세션도, 메모리도 아니다).
        RedisOAuth2AuthorizationRequestRepository podA = newRepository(600);
        RedisOAuth2AuthorizationRequestRepository podB = newRepository(600);

        OAuth2AuthorizationRequest original = requestWithState("state-cross-pod");
        String verifier = (String) original.getAttributes().get("code_verifier");

        // ① 파드 A 가 로그인을 시작한다.
        podA.saveAuthorizationRequest(original, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        // ② 프로바이더가 파드 B 로 돌려보낸다.
        OAuth2AuthorizationRequest restored = podB.removeAuthorizationRequest(
                callbackWith("state-cross-pod"), new MockHttpServletResponse());

        assertThat(restored).as("파드 B 가 파드 A 의 인가 요청을 찾지 못하면 로그인이 깨진다")
                .isNotNull();
        assertThat(restored.getState()).isEqualTo("state-cross-pod");
        // verifier 가 없으면 토큰 교환이 invalid_grant 로 실패한다.
        assertThat(restored.getAttributes().get("code_verifier")).isEqualTo(verifier);
    }

    @Test
    @DisplayName("한 번 꺼내면 사라진다 — 콜백 재생(replay)을 막는다")
    void removedRequest_cannotBeUsedTwice() {
        RedisOAuth2AuthorizationRequestRepository repository = newRepository(600);
        repository.saveAuthorizationRequest(requestWithState("state-once"),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        OAuth2AuthorizationRequest first = repository.removeAuthorizationRequest(
                callbackWith("state-once"), new MockHttpServletResponse());
        OAuth2AuthorizationRequest second = repository.removeAuthorizationRequest(
                callbackWith("state-once"), new MockHttpServletResponse());

        assertThat(first).isNotNull();
        // GETDEL 이 원자적이라서 두 번째는 반드시 빈손이다. GET 후 DELETE 로 나눴다면
        // 두 콜백이 동시에 도착할 때 둘 다 성공할 수 있다.
        assertThat(second).isNull();
        assertThat(template.hasKey("oauth2:authreq:state-once")).isFalse();
    }

    @Test
    @DisplayName("TTL 이 걸린다 — 중단된 로그인이 영원히 남지 않는다")
    void savedRequest_hasTtl() {
        newRepository(600).saveAuthorizationRequest(requestWithState("state-ttl"),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        Long ttl = template.getExpire("oauth2:authreq:state-ttl", TimeUnit.SECONDS);

        assertThat(ttl).isNotNull();
        // -1 = TTL 없음(영구), -2 = 키 없음. 둘 다 실패다.
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(600L);
    }

    @Test
    @DisplayName("만료된 인가 요청은 없는 것과 같다")
    void expiredRequest_isGone() throws InterruptedException {
        newRepository(1).saveAuthorizationRequest(requestWithState("state-expire"),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        // TTL 1초. 실제로 지나가기를 기다린다 — 가짜 시계로는 Redis 의 만료를 검증할 수 없다.
        TimeUnit.MILLISECONDS.sleep(1500);

        assertThat(newRepository(600).loadAuthorizationRequest(callbackWith("state-expire")))
                .isNull();
    }

    @Test
    @DisplayName("모르는 state 로 온 콜백은 null — 위조를 조용히 통과시키지 않는다")
    void unknownState_returnsNull() {
        assertThat(newRepository(600).removeAuthorizationRequest(
                callbackWith("state-never-issued"), new MockHttpServletResponse())).isNull();
    }

    @Test
    @DisplayName("서로 다른 로그인 시도는 서로를 덮어쓰지 않는다")
    void concurrentLogins_areIndependent() {
        RedisOAuth2AuthorizationRequestRepository repository = newRepository(600);
        OAuth2AuthorizationRequest alice = requestWithState("state-alice");
        OAuth2AuthorizationRequest bob = requestWithState("state-bob");

        repository.saveAuthorizationRequest(alice, new MockHttpServletRequest(),
                new MockHttpServletResponse());
        repository.saveAuthorizationRequest(bob, new MockHttpServletRequest(),
                new MockHttpServletResponse());

        OAuth2AuthorizationRequest restoredAlice = repository.removeAuthorizationRequest(
                callbackWith("state-alice"), new MockHttpServletResponse());
        OAuth2AuthorizationRequest restoredBob = repository.removeAuthorizationRequest(
                callbackWith("state-bob"), new MockHttpServletResponse());

        assertThat(restoredAlice.getAttributes().get("code_verifier"))
                .isEqualTo(alice.getAttributes().get("code_verifier"));
        assertThat(restoredBob.getAttributes().get("code_verifier"))
                .isEqualTo(bob.getAttributes().get("code_verifier"));
        assertThat(restoredAlice.getAttributes().get("code_verifier"))
                .isNotEqualTo(restoredBob.getAttributes().get("code_verifier"));
    }
}
