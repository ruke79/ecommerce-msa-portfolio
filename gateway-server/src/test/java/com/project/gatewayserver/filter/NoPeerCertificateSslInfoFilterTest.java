package com.project.gatewayserver.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Ssl;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 손님 쪽 TLS 정보에서 인증서를 묻지 않는다 — docs/83 §5(2026-10-23 CPU 프로파일).
 *
 * <p>라우트 필터의 {@code mutate()} 가 원래 요청의 {@code getSslInfo()} 를 부르고, 그 첫 호출이 {@code getPeerCertificates()} 로
 * 예외를 만든다(게이트웨이 CPU 의 2.6%). 진짜 Netty 요청 대신 {@code getSslInfo()} 호출 수를 세는 요청으로 같은 길을 밟는다.
 *
 * <p>[JA] mutate() のたびに元のリクエストの getSslInfo() が呼ばれ、証明書の問い合わせで例外が作られていた。呼び出し数を数える。
 */
@DisplayName("손님 쪽 TLS 정보 — 클라이언트 인증서를 받지 않으면 묻지 않는다")
class NoPeerCertificateSslInfoFilterTest {

    /** getSslInfo() 를 부를 때마다 센다 — 진짜 요청이라면 여기서 getPeerCertificates() 예외가 난다. */
    private static ServerWebExchange exchange(String url, AtomicInteger lookups) {
        ServerHttpRequest counting = new ServerHttpRequestDecorator(MockServerHttpRequest.get(url).build()) {
            @Override
            public SslInfo getSslInfo() {
                lookups.incrementAndGet();
                return super.getSslInfo();
            }
        };
        return MockServerWebExchange.from(MockServerHttpRequest.get(url)).mutate().request(counting).build();
    }

    /** 라우트 필터(RemoveRequestHeader 등)처럼 요청을 고치고, 고친 요청의 TLS 정보를 돌려준다. */
    private static SslInfo runAndMutate(NoPeerCertificateSslInfoFilter filter, ServerWebExchange exchange) {
        AtomicReference<SslInfo> seen = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            ServerHttpRequest mutated = ex.getRequest().mutate().header("X-Test", "1").build();
            seen.set(mutated.getSslInfo());
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return seen.get();
    }

    @Test
    @DisplayName("🔴 client-auth 없음 · NONE — https 요청을 고쳐도 원래 요청의 TLS 정보(인증서 조회)를 부르지 않는다")
    void doesNotLookUpPeerCertificatesWhenClientAuthIsOff() {
        for (Ssl.ClientAuth auth : new Ssl.ClientAuth[] {null, Ssl.ClientAuth.NONE}) {
            AtomicInteger lookups = new AtomicInteger();
            SslInfo info = runAndMutate(new NoPeerCertificateSslInfoFilter(auth), exchange("https://shop.lab/catalog-service/api/x", lookups));

            assertThat(lookups).as("client-auth=%s", auth).hasValue(0);
            assertThat(info).as("보안 연결로는 보인다 — 인증서만 없다").isNotNull();
            assertThat(info.getPeerCertificates()).isNull();
        }
    }

    @Test
    @DisplayName("WANT · NEED 는 인증서가 진짜로 있을 수 있다 — 원래 요청에 묻는다. http 요청도 그대로")
    void leavesRequestAloneWhenCertificatesMayExistOrPlainHttp() {
        for (Ssl.ClientAuth auth : new Ssl.ClientAuth[] {Ssl.ClientAuth.WANT, Ssl.ClientAuth.NEED}) {
            AtomicInteger lookups = new AtomicInteger();
            runAndMutate(new NoPeerCertificateSslInfoFilter(auth), exchange("https://shop.lab/a", lookups));
            assertThat(lookups).as("client-auth=%s", auth).hasPositiveValue();
        }
        AtomicInteger lookups = new AtomicInteger();
        SslInfo info = runAndMutate(new NoPeerCertificateSslInfoFilter(Ssl.ClientAuth.NONE), exchange("http://localhost:8000/a", lookups));
        assertThat(lookups).hasPositiveValue();
        assertThat(info).as("http 는 보안 연결이 아니다 — 가짜 TLS 정보를 붙이지 않는다").isNull();
    }

    @Test
    @DisplayName("어느 필터가 요청을 고치기보다 먼저 — 가장 높은 우선순위")
    void runsFirst() {
        assertThat(new NoPeerCertificateSslInfoFilter((Ssl.ClientAuth) null).getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
