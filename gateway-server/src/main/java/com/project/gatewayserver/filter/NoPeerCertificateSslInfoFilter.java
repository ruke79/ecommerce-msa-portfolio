package com.project.gatewayserver.filter;

import java.security.cert.X509Certificate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * 손님 쪽 리스너가 클라이언트 인증서를 받지 않으면, 요청의 TLS 정보({@link SslInfo})를 <b>인증서를 묻지 않는 값</b>으로 바꿔 끼운다.
 *
 * <p>🐛 2026-10-23 CPU 프로파일(capacity-steps 300 단계, docs/83 §5 · ops/58): 게이트웨이 CPU 의 2.6% 가 요청마다 만들어 버리는 예외 하나였다.
 * 라우트 필터가 요청을 고칠 때({@code exchange.getRequest().mutate()}) Spring 의 {@code DefaultServerHttpRequestBuilder} 가 원래 요청의
 * {@code getSslInfo()} 를 복사하고, 그 첫 호출이 {@code DefaultSslInfo} 를 만들며 {@code SSLSession.getPeerCertificates()} 를 부른다.
 * 손님(브라우저 · 터널 · Traefik)은 인증서가 없으니 JDK 는 매번 {@code SSLPeerUnverifiedException} 을 스택 추적과 함께 만들고,
 * Spring 은 그것을 잡아 버리고 {@code null} 을 쓴다(spring-web 6.2.19 바이트코드로 확인). 리액터 체인 위라 스택이 깊어 비싸다.
 *
 * <p>받지 않는 설정({@code server.ssl.client-auth} 가 없거나 NONE)이면 인증서는 늘 없다 — 묻지 않아도 답은 같다. 그래서 https 요청의
 * {@code getSslInfo()} 를 "보안 연결 · 인증서 없음" 상수로 답한다(세션 id 는 비운다 — 게이트웨이에서 읽는 곳이 없다).
 * WANT · NEED 로 바꾸면 이 필터는 아무것도 하지 않는다 — 인증서가 진짜로 있을 수 있다. http 요청도 그대로(원래도 null, 싸다).
 *
 * <p>[JA] 客側のリスナーがクライアント証明書を受けない設定なら、TLS 情報を「証明書を問わない」値に差し替える。リクエストを mutate する
 * たびに Spring が getPeerCertificates() を呼び、JDK が毎回 SSLPeerUnverifiedException をスタックトレース付きで作って捨てていた
 * (ゲートウェイ CPU の 2.6%)。client-auth が WANT · NEED なら何もしない。
 */
@Component
public class NoPeerCertificateSslInfoFilter implements WebFilter, Ordered {

    /** 보안 연결 · 클라이언트 인증서 없음. ／ [JA] 安全な接続 · クライアント証明書なし。 */
    static final SslInfo NO_PEER_CERTIFICATES = new SslInfo() {
        @Override
        @Nullable
        public String getSessionId() {
            return null;
        }

        @Override
        @Nullable
        public X509Certificate[] getPeerCertificates() {
            return null;
        }
    };

    private final boolean enabled;

    @Autowired
    public NoPeerCertificateSslInfoFilter(ServerProperties server) {
        this(server.getSsl() == null ? null : server.getSsl().getClientAuth());
    }

    NoPeerCertificateSslInfoFilter(@Nullable Ssl.ClientAuth clientAuth) {
        this.enabled = clientAuth == null || clientAuth == Ssl.ClientAuth.NONE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!enabled || !"https".equalsIgnoreCase(request.getURI().getScheme())) {
            return chain.filter(exchange);
        }
        ServerHttpRequest withoutPeerLookup = new ServerHttpRequestDecorator(request) {
            @Override
            public SslInfo getSslInfo() {
                return NO_PEER_CERTIFICATES;
            }
        };
        return chain.filter(exchange.mutate().request(withoutPeerLookup).build());
    }

    @Override
    public int getOrder() {
        // 어느 WebFilter · 라우트 필터가 요청을 고치기(mutate)보다 먼저 ／ [JA] どのフィルターが mutate するより先に
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
