package com.project.gatewayserver.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * 레이트리미터 버킷 키 — <b>클라이언트가 고를 수 있는 값은 키가 될 수 없다.</b>
 *
 * <p>www-security 데모 시나리오 1(X-Forwarded-For 스푸핑으로 Rate Limit 우회)을 이
 * 게이트웨이에 적용한 회귀 테스트다. 예전 테스트는 정반대를 고정하고 있었다 —
 * "X-User-Id 가 있으면 user: 키를 쓴다", "XFF 의 첫 IP 로 폴백한다". 둘 다 클라이언트가
 * 요청마다 바꿀 수 있는 값이라, 그 테스트는 우회 경로를 <b>보호</b>하고 있던 셈이다.
 */
class CustomUserKeyResolverTest {

    private final KeyResolver resolver = new CustomUserKeyResolver().userKeyResolver();

    @Test
    @DisplayName("게이트웨이가 넣은 User-Id 가 있으면 사용자 단위 키")
    void authenticatedUser_usesUserKey() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x").header("User-Id", "42"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("user:42");
    }

    @Test
    @DisplayName("클라이언트가 보낸 X-User-Id 는 키가 되지 않는다 — 요청마다 새 버킷을 만들 수 있었다")
    void spoofedXUserId_isIgnored() {
        // 게이트웨이는 X-User-Id 를 넣지 않는다. 이 헤더가 있다면 클라이언트가 보낸 것이다.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header("X-User-Id", "i-pick-a-new-one-each-request")
                        .remoteAddress(new InetSocketAddress("9.9.9.9", 12345)));

        String key = resolver.resolve(exchange).block();
        assertThat(key)
                .as("X-User-Id 를 믿으면 비로그인 클라이언트가 값을 바꿔 가며 제한을 우회한다")
                .doesNotStartWith("user:")
                .isEqualTo("ip:9.9.9.9");
    }

    @Test
    @DisplayName("X-Forwarded-For 는 무시한다 — 첫 항목은 클라이언트가 보낸 값이다")
    void forwardedFor_isIgnored() {
        // 프록시는 XFF 에 덧붙이지 지우지 않는다. 첫 항목은 클라이언트가 처음 쓴 것.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .remoteAddress(new InetSocketAddress("9.9.9.9", 12345)));

        assertThat(resolver.resolve(exchange).block())
                .as("XFF 첫 항목을 쓰면 요청마다 IP 를 바꿔 제한을 우회한다")
                .isEqualTo("ip:9.9.9.9");
    }

    @Test
    @DisplayName("Cloudflare 엣지가 채운 CF-Connecting-IP 를 원격 주소보다 우선한다")
    void cloudflareConnectingIp_wins() {
        // 터널 뒤에서는 원격 주소가 항상 cloudflared 파드다. 엣지가 덮어쓰는 이 헤더가
        // 실제 접속 IP 이고, 클라이언트가 같은 이름을 보내도 엣지에서 교체된다.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header("CF-Connecting-IP", "203.0.113.7")
                        .header("X-Forwarded-For", "1.2.3.4")
                        .remoteAddress(new InetSocketAddress("10.42.0.5", 12345)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:203.0.113.7");
    }

    @Test
    @DisplayName("헤더가 없으면 소켓 원격 주소")
    void anonymous_fallsBackToRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .remoteAddress(new InetSocketAddress("9.9.9.9", 12345)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:9.9.9.9");
    }

    @Test
    @DisplayName("아무 정보도 없어도 절대 null/empty 키를 반환하지 않는다 (NPE 회귀 방지)")
    void noInformation_neverReturnsNullKey() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x"));

        String key = resolver.resolve(exchange).block();
        assertThat(key).isNotNull().isNotEmpty();
    }
}
