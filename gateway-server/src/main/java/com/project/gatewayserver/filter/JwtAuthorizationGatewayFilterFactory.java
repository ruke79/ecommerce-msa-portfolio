package com.project.gatewayserver.filter;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.project.gatewayserver.i18n.GatewayText;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 라우트 필터 {@code JwtAuthorization} — 액세스 토큰을 검증하고, 검증된 클레임에서만 신원 헤더(User-Id · User-Email · User-Role)를 싣는다.
 *
 * <p>토큰은 {@code Authorization: Bearer …} 에서, 없으면 {@code ACCESS} 쿠키에서. 서명 · 만료 · 종류를 <b>한 번의 파싱</b>으로 본다 —
 * 예전에는 같은 토큰을 요청마다 여섯 번(만료 · 상태 · 종류 · subject · role · uid) HMAC 검증했다.
 *
 * <p>라우트 목록에서 {@code RemoveRequestHeader=Cookie} 보다 앞에 둔다(RateLimiterPlacementTest) — 쿠키를 지운 뒤 찾지 않는다.
 *
 * <p>[JA] ルートフィルター JwtAuthorization — アクセストークンを検証し、検証済みクレームからだけ身元ヘッダーを載せる。署名・期限・種別を
 * 一回のパースで見る(以前は同じトークンをリクエストごとに六回検証していた)。
 */
@Slf4j
@Component
public class JwtAuthorizationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthorizationGatewayFilterFactory.Config> {

    /** 설정 항목 없음 — 예전의 exceptPaths 는 어느 라우트도 쓰지 않았고, 경로 재작성 앞뒤에 따라 뜻이 달라져 지웠다. */
    public static class Config {
    }

    static final String TOKEN_TYPE_CLAIM = "tokenType";
    static final String ACCESS = "access";

    @Value("${spring.app.jwtSecret}")
    private String secretKey;

    /** 서명 키 링(docs/68 §17) — user-service 와 같은 값을 본다. 비어 있으면 JWT_SECRET_KEY 하나(kid "legacy"). */
    // [JA] 署名鍵リング — user-service と同じ値を見る。空なら JWT_SECRET_KEY 一つ。
    @Value("${spring.app.jwtSigningKeys:}")
    private String jwtSigningKeys = "";

    @Value("${spring.app.jwtActiveKid:}")
    private String jwtActiveKid = "";

    @Value("${spring.app.jwtAcceptLegacy:true}")
    private boolean jwtAcceptLegacy = true;

    private volatile String ringSource;
    private volatile com.project.common.security.JwtKeyRing ring;

    public JwtAuthorizationGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            String jwt = bearer(request);
            if (jwt == null) {
                HttpCookie accessCookie = request.getCookies().getFirst("ACCESS");
                jwt = accessCookie == null ? null : accessCookie.getValue();
            }
            if (jwt == null) {
                return unauthorized(exchange, "no token", GatewayText.pick(exchange,
                        "로그인이 필요한 서비스입니다.", "ログインが必要なサービスです。"));
            }

            Claims claims;
            try {
                claims = Jwts.parser().keyLocator(keyLocator()).build().parseSignedClaims(jwt).getPayload();
            } catch (ExpiredJwtException e) {
                // 만료는 정상 흐름이다 — 화면이 401 을 받으면 리프레시로 새 토큰을 받는다
                return unauthorized(exchange, "expired", relogin(exchange));
            } catch (JwtException | IllegalArgumentException e) {
                // 🐛 예전: 서명이 안 맞는 토큰 · 모르는 kid 는 예외가 그대로 올라가 500 이었다. 401 이어야 화면이 리프레시로 새 토큰을
                //    받는다 — 키를 은퇴시킨 직후(docs/68 §17) 옛 키의 토큰을 든 사용자가 500 을 보게 된다. 종류만 남긴다(토큰은 남기지 않는다).
                // [JA] 以前は署名不一致 · 未知の kid の例外がそのまま上がり 500 だった。401 なら画面がリフレッシュで新しいトークンを受け取る。
                return unauthorized(exchange, e.getClass().getSimpleName(), relogin(exchange));
            }

            // 🔒 액세스 토큰만 신원으로 받는다. 예전에는 서명·만료만 봐서 14일짜리 리프레시 토큰(같은 키로 서명)이 Bearer 로
            //    통했고, 로그아웃 뒤에도 그랬다(user-service JwtUtils.TOKEN_TYPE_CLAIM). 종류 클레임이 없는 옛 액세스 토큰도
            //    여기서 한 번 401 — 화면은 401 이면 리프레시로 새 토큰을 받는다.
            // [JA] 🔒 アクセストークンだけを身元として受け付ける。リフレッシュトークン(同じ鍵で署名)が Bearer として通っていた。
            if (!ACCESS.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
                return unauthorized(exchange, "not an access token", relogin(exchange));
            }

            // 불변 숫자 id 가 소유권 판정의 기준이다(이메일은 바뀔 수 있다). uid 클레임이 없는 토큰(이 변경 이전 · OAuth 경로)은
            // 신원을 확인할 수 없으므로 끊는다 — 다시 로그인하면 새 토큰을 받는다.
            // [JA] 不変の数値 id が所有権判定の基準。uid クレームのないトークンは身元を確認できないので遮断する。
            Long uid = uid(claims.get("uid"));
            if (uid == null) {
                return unauthorized(exchange, "no uid claim", relogin(exchange));
            }
            String email = claims.getSubject();
            Object role = claims.get("role");

            ServerHttpRequest.Builder mutated = request.mutate()
                    // ⚠️ 클라이언트가 직접 보낸 값을 반드시 지운다 — 값이 없어 아래 세팅을 건너뛰면 클라이언트 헤더가 그대로 하위로 간다.
                    //    특히 User-Role 은 지우지 않으면 권한 상승으로 직결된다(IdentityHeaderScrubFilter 와 이중).
                    // [JA] ⚠️ クライアントが直接送った値を必ず消す — 特に User-Role は消さなければ権限昇格に直結する。
                    .headers(h -> {
                        h.remove("User-Id");
                        h.remove("User-Email");
                        h.remove("User-Role");
                    })
                    .header("User-Id", String.valueOf(uid));
            if (email != null) {
                mutated.header("User-Email", email);
            }
            if (role != null) {
                mutated.header("User-Role", role.toString());
            }
            return chain.filter(exchange.mutate().request(mutated.build()).build());
        };
    }

    private static String bearer(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()) : null;
    }

    /** jjwt 는 JSON 숫자를 Integer 또는 Long 으로 준다 — 둘 다 받는다. 숫자가 아니면 없는 것으로 본다. */
    // [JA] jjwt は JSON の数値を Integer か Long で返す — 両方受け付ける。数値でなければないものとする。
    static Long uid(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return raw == null ? null : Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String relogin(ServerWebExchange exchange) {
        return GatewayText.pick(exchange, "인증이 만료되었거나 올바르지 않습니다. 다시 로그인해 주세요.",
                "認証の有効期限が切れたか、正しくありません。もう一度ログインしてください。");
    }

    com.project.common.security.JwtKeyRing ring() {
        // 설정 리프레시를 따라간다. 검사와 대입이 원자적이지 않아 동시에 두 번 만들 수 있지만 같은 값이라 무해하다.
        String source = secretKey + "\u0000" + jwtSigningKeys + "\u0000" + jwtActiveKid + "\u0000" + jwtAcceptLegacy;
        if (!source.equals(ringSource)) {
            ring = com.project.common.security.JwtKeyRing.of(secretKey, jwtSigningKeys, jwtActiveKid, jwtAcceptLegacy);
            ringSource = source;
        }
        return ring;
    }

    /** 기동 때 한 번 — 키 설정이 틀렸으면 뜨지 않는다(검증만 하는 쪽도 모르는 키로 서명된 토큰을 받게 두지 않는다). */
    @jakarta.annotation.PostConstruct
    void checkKeyRing() {
        ring();
    }

    /** 토큰 머리의 kid 로 검증 키를 고른다 — kid 가 없으면 옛 토큰(legacy), 모르는 kid 는 거절(docs/68 §17). */
    private io.jsonwebtoken.Locator<java.security.Key> keyLocator() {
        return header -> {
            String kid = header instanceof io.jsonwebtoken.ProtectedHeader ph ? ph.getKeyId() : null;
            return ring().verificationKey(kid)
                    .map(b -> (java.security.Key) new SecretKeySpec(b, "HmacSHA256"))
                    .orElseThrow(() -> new UnsupportedJwtException("알 수 없는 서명 키"));
        };
    }

    /**
     * 401 과 요청 언어의 문구. 로그는 한 줄 · DEBUG — 만료 · 미로그인은 정상 흐름이라 ERROR 로 두면 경보 잡음이 된다(예전에는 문구 그대로
     * ERROR 두 번). 토큰 · 신원은 남기지 않는다.
     */
    // [JA] 401 とリクエスト言語の文言。ログは一行・DEBUG — 期限切れ・未ログインは正常な流れ。トークン・身元は残さない。
    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason, String message) {
        log.debug("401 — {} ({})", reason, exchange.getRequest().getPath().value());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(message.getBytes(StandardCharsets.UTF_8))));
    }
}
