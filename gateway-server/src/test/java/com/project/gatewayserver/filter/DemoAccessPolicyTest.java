package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import io.jsonwebtoken.Jwts;
import reactor.core.publisher.Mono;

/**
 * 공개 데모 계정 — 게이트웨이가 계정 · 자격 · 리뷰 · 인증 SMS 의 쓰기를 403 으로 막는다(docs/100).
 *
 * <p>여러 방문자가 한 계정을 돌려 쓰므로 비밀번호 · 2단계 인증 · 모든 기기 로그아웃 · 탈퇴 하나로 다른 방문자가 못 들어온다. 판정은
 * RewritePath 전의 원래 경로(서비스 이름까지)로 한다 — 필터는 재작성 뒤에 돈다.
 *
 * <p>[JA] 公開デモアカウント — ゲートウェイがアカウント · 資格 · レビュー · 認証 SMS の書き込みを 403 で止める。判定は RewritePath 前の
 *      元の経路(サービス名まで)で行う — フィルターは書き換えの後に回る。
 */
@DisplayName("데모 계정 — 게이트웨이의 쓰기 차단")
class DemoAccessPolicyTest {

    private static final String SECRET = "gateway-test-secret-gateway-test-secret-01";

    private static String token(Boolean demo) {
        var b = Jwts.builder().subject("demo1@demo.invalid").claim("uid", 7L).claim("role", "ROLE_USER").claim("tokenType", "access");
        if (demo != null) {
            b.claim("demo", demo);
        }
        return b.issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")).compact();
    }

    /** 원래 경로로 들어와 RewritePath 로 바뀐 요청을 흉내 낸다. 통과하면 null. [JA] 通れば null。 */
    private static HttpStatus run(HttpMethod method, String originalPath, Boolean demo) {
        JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(factory, "secretKey", SECRET);
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        String rewritten = originalPath.replaceFirst("^/[^/]+-service", "");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, URI.create("https://gw" + rewritten)).header("Authorization", "Bearer " + token(demo)));
        Set<URI> originals = new LinkedHashSet<>();
        originals.add(URI.create("https://gw" + originalPath));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR, originals);
        AtomicBoolean passed = new AtomicBoolean();
        filter.filter(exchange, e -> {
            passed.set(true);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return passed.get() ? null : (HttpStatus) exchange.getResponse().getStatusCode();
    }

    @Test
    @DisplayName("🔴 데모 토큰 — 비밀번호 · 2단계 인증 · 모든 기기 로그아웃 · 탈퇴 · 패스키 · 리뷰 · 인증 SMS 는 403")
    void demoWritesAreBlocked() {
        assertThat(run(HttpMethod.PUT, "/user-service/api/user/profile/update-password", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.PUT, "/user-service/api/user/profile/me", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/user/profile/me/image", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/auth/verify-2fa", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/auth/sms-2fa/enable", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/user/profile/sessions/revoke-all", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/user/withdrawal", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/user/passkeys", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/review/add/5", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/notification-service/api/notifications/phone/verification", true))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("🔴 겹친 '/' · 끝 '/' · 인코딩으로 돌아가지 못한다")
    void bypassVariantsAreBlocked() {
        assertThat(run(HttpMethod.PUT, "/user-service//api/user/profile/update-password/", true)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(run(HttpMethod.POST, "/user-service/api/user/%77ithdrawal", true)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("데모 토큰이라도 읽기 · 장바구니 · 주문 · 주소록 · 알림 · 채팅은 통과")
    void demoAllowedActionsPass() {
        assertThat(run(HttpMethod.GET, "/user-service/api/user/profile/me", true)).isNull();
        assertThat(run(HttpMethod.GET, "/user-service/api/review/reviews/5", true)).isNull();
        assertThat(run(HttpMethod.POST, "/cart-service/api/cart/savecart", true)).isNull();
        assertThat(run(HttpMethod.POST, "/order-service/api/order/create", true)).isNull();
        assertThat(run(HttpMethod.POST, "/user-service/api/user/profile/save_address", true)).isNull();
        assertThat(run(HttpMethod.PATCH, "/notification-service/api/notifications/read-all", true)).isNull();
        assertThat(run(HttpMethod.POST, "/chat-service/api/chat/support/messages", true)).isNull();
    }

    @Test
    @DisplayName("🔴 데모 표시가 없거나 false 인 토큰은 같은 경로도 통과 — 보통 계정은 막지 않는다")
    void regularTokensAreNotBlocked() {
        assertThat(run(HttpMethod.PUT, "/user-service/api/user/profile/update-password", null)).isNull();
        assertThat(run(HttpMethod.PUT, "/user-service/api/user/profile/update-password", false)).isNull();
    }

    /** 통과한 요청이 하위 서비스로 들고 간 User-Demo. [JA] 通過した要求が下位へ持って行った User-Demo。 */
    private static String forwardedDemoHeader(Boolean demo, String spoofed) {
        JwtAuthorizationGatewayFilterFactory factory = new JwtAuthorizationGatewayFilterFactory();
        ReflectionTestUtils.setField(factory, "secretKey", SECRET);
        GatewayFilter filter = factory.apply(new JwtAuthorizationGatewayFilterFactory.Config());
        var req = MockServerHttpRequest.post("https://gw/api/order/create").header("Authorization", "Bearer " + token(demo));
        if (spoofed != null) {
            req.header("User-Demo", spoofed);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>("<none>");
        filter.filter(exchange, e -> {
            seen.set(e.getRequest().getHeaders().getFirst("User-Demo"));
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return seen.get();
    }

    @Test
    @DisplayName("🔴 데모 토큰만 User-Demo: true 를 싣는다 — 보통 토큰은 손님이 보낸 값도 지운다")
    void demoHeaderOnlyFromVerifiedClaim() {
        assertThat(forwardedDemoHeader(true, null)).isEqualTo("true");
        assertThat(forwardedDemoHeader(null, null)).isNull();
        assertThat(forwardedDemoHeader(null, "true")).as("손님이 보낸 User-Demo").isNull();
        assertThat(IdentityHeaderScrubFilter.IDENTITY_HEADERS).contains("User-Demo");
    }
}
