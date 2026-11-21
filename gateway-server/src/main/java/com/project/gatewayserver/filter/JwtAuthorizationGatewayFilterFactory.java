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
    // [JA] 設定項目なし — 以前の exceptPaths はどのルートも使っておらず、パス書き換えの前後で意味が変わるので削除した。
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

    /**
     * 로그인 해제 시각 확인(docs/68 §7 · docs/57 §22) — 끄면 예전처럼 서명 · 만료만 본다. [JA] ログイン解除時刻の確認 — 切ると以前どおり。
     */
    @Value("${gateway.session-cutoff.enabled:true}")
    private boolean sessionCutoffEnabled = true;

    /** Redis 조회 상한 — 넘으면 막지 않고 보낸다(유입 제한과 같은 1초 아래, B-84). [JA] Redis 照会の上限 — 超えたら通す。 */
    @Value("${gateway.session-cutoff.timeout:300ms}")
    private java.time.Duration sessionCutoffTimeout = java.time.Duration.ofMillis(300);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.data.redis.core.ReactiveStringRedisTemplate redis;

    /**
     * 해제 확인의 결과를 센다 — {@value #CUTOFF_METRIC}{@code {result}}: {@code revoked}(해제 뒤의 토큰을 401) · {@code error} ·
     * {@code timeout} · {@code no_redis}(확인하지 못해 통과). 🐛 예전에는 통과도 거절도 debug 로그뿐이라, 2026-11-21 운영에서
     * "비밀번호를 재설정했는데 다른 브라우저가 그대로" 일 때 게이트웨이가 401 을 줬는지조차 알 수 없었다.
     */
    // [JA] 解除確認の結果を数える。以前は通過も拒否も debug ログだけで、2026-11-21 本番で別ブラウザが残ったとき 401 を返したかすら分からなかった。
    static final String CUTOFF_METRIC = "gateway.session.cutoff";
    /** 데모 계정이 막힌 동작으로 403 을 받은 수. [JA] デモアカウントが止められた操作で 403 を受けた数。 */
    static final String DEMO_BLOCKED_METRIC = "gateway.demo.blocked";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    /** 확인을 건너뛴 경고는 분마다 한 줄 — Redis 가 죽으면 모든 인증 요청이 건너뛴다. [JA] スキップの警告は 1 分に 1 行。 */
    private final java.util.concurrent.atomic.AtomicLong lastSkipWarnMs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong skippedSinceWarn = new java.util.concurrent.atomic.AtomicLong();
    static final long SKIP_WARN_INTERVAL_MS = 60_000;

    /** 기동 때 0 으로 만든다(B-101 — 첫 사고에 생기면 increase() 가 0 으로 본다). 확인이 꺼져 있으면 한 줄 남긴다. */
    // [JA] 起動時に 0 で作る(B-101)。確認が無効なら一行残す。
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        for (String result : new String[] {"revoked", "error", "timeout", "no_redis"}) {
            cutoffCounter(result);
        }
        if (!sessionCutoffEnabled) {
            log.warn("[auth] 로그인 해제 확인 꺼짐(gateway.session-cutoff.enabled=false) — 해제 뒤의 액세스 토큰이 만료까지 통한다");
        } else if (redis == null) {
            log.warn("[auth] 로그인 해제 확인을 할 수 없다 — Redis 템플릿 없음, 해제 뒤의 액세스 토큰이 만료까지 통한다");
        }
    }

    private io.micrometer.core.instrument.Counter cutoffCounter(String result) {
        return io.micrometer.core.instrument.Counter.builder(CUTOFF_METRIC)
                .description("로그인 해제 확인 결과 — revoked 는 401, 나머지는 확인하지 못해 통과")
                .tag("result", result).register(meters);
    }

    /** 확인하지 못해 통과시켰다 — 세고, 분마다 한 줄 경고(그 사이 건수와 함께). 토큰 · 이메일은 남기지 않는다. */
    // [JA] 確認できずに通した — 数えて、1 分ごとに一行警告。トークン · メールは残さない。
    private void skipped(String result, Long uid, Throwable cause) {
        cutoffCounter(result).increment();
        long n = skippedSinceWarn.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastSkipWarnMs.get();
        if (now - last >= SKIP_WARN_INTERVAL_MS && lastSkipWarnMs.compareAndSet(last, now)) {
            skippedSinceWarn.addAndGet(-n);
            log.warn("[auth] 로그인 해제 시각을 확인하지 못해 통과 — result={} uid={} 원인={} (지난 경고 뒤 {}건)", result, uid,
                    cause == null ? "-" : cause.getClass().getSimpleName(), n);
        }
    }

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
                // [JA] 期限切れは正常な流れである — 画面が 401 を受けるとリフレッシュで新しいトークンを受け取る
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
            // 🔒 공개 데모 계정은 계정 · 자격 · 남에게 보이는 글 · 인증 SMS 를 바꾸지 못한다 — 여러 방문자가 돌려 쓰므로(DemoAccessPolicy).
            //    경로는 RewritePath 전의 원래 경로로 본다(서비스 이름까지).
            // [JA] 🔒 公開デモアカウントはアカウント · 資格 · 他人に見える投稿 · 認証 SMS を変えられない — 複数の訪問者が使い回すため。
            if (Boolean.TRUE.equals(claims.get(DemoAccessPolicy.DEMO_CLAIM, Boolean.class))
                    && DemoAccessPolicy.blocked(request.getMethod(), originalUri(exchange))) {
                return demoForbidden(exchange);
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
                        h.remove("User-Demo");
                    })
                    .header("User-Id", String.valueOf(uid));
            if (email != null) {
                mutated.header("User-Email", email);
            }
            if (role != null) {
                mutated.header("User-Role", role.toString());
            }
            // 공개 데모 계정 — 하위 서비스가 데모만의 상한(결제 전 주문 수 등)을 건다(docs/100). 검증된 클레임에서만.
            // [JA] 公開デモアカウント — 下位サービスがデモだけの上限(決済前の注文数など)をかける。検証済みクレームからだけ。
            if (Boolean.TRUE.equals(claims.get(DemoAccessPolicy.DEMO_CLAIM, Boolean.class))) {
                mutated.header("User-Demo", "true");
            }
            ServerWebExchange forwarded = exchange.mutate().request(mutated.build()).build();
            return sessionStillValid(uid, claims).flatMap(valid -> {
                if (valid) {
                    return chain.filter(forwarded);
                }
                // 드물다(해제 직후 그 사용자의 다른 기기만) — 한 줄씩 남긴다. uid 와 경로만.
                // [JA] まれ(解除直後のそのユーザーの他の端末だけ) — 一行ずつ。uid とパスだけ。
                cutoffCounter("revoked").increment();
                log.info("[auth] 로그인 해제 뒤의 액세스 토큰 — 401 uid={} 경로={}", uid, request.getPath().value());
                return unauthorized(exchange, "session revoked", relogin(exchange));
            });
        };
    }

    /**
     * 로그인 해제 뒤의 액세스 토큰을 거절한다 — {@code auth:cutoff:<uid>}(user-service 가 "모든 기기에서 로그아웃" · 비밀번호 변경 ·
     * 재설정 · 관리자 잠금 · 탈퇴 · 리프레시 재사용 감지 때 쓴다) 보다 이른 {@code iat} 는 401.
     *
     * <p>🐛 예전에는 게이트웨이가 서명 · 만료만 봐서, 훔친 액세스 토큰은 그 사건들 뒤에도 수명(최대 15분)까지 주문 · 결제 · 개인정보 API 에
     * 통했다(docs/68 §7 — "15분이면 충분" 이라 두었던 것). 긴 연결(채팅 · 알림)은 이미 같은 값으로 즉시 끊었다(docs/68 §9).
     * 비용: 인증 요청마다 Redis GET 하나(키가 없으면 빈 값 — 대부분). Redis 가 느리거나 죽으면 막지 않는다 — 전체 장애로 키우지 않고,
     * 만료가 여전히 15분 상한을 건다. 이 기기의 새 토큰(재발급 · 갱신)은 iat ≥ 해제 시각이라 통한다.
     *
     * <p>[JA] ログイン解除後のアクセストークンを拒否する — auth:cutoff より早い iat は 401。以前は署名 · 期限だけで、盗まれたトークンが
     * 最大 15 分通った。Redis が遅い · 落ちているときは通す(全体障害にしない、期限が 15 分の上限)。
     */
    Mono<Boolean> sessionStillValid(Long uid, Claims claims) {
        if (!sessionCutoffEnabled) {
            return Mono.just(true);
        }
        if (redis == null) {
            skipped("no_redis", uid, null);
            return Mono.just(true);
        }
        java.util.Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null) {
            return Mono.just(true);   // iat 없는 토큰은 만들지 않는다(JwtUtils) — 모르는 모양 때문에 모두를 막지 않는다 ／ [JA] iat のないトークンは作らない(JwtUtils) — 知らない形のせいで全員を塞がない
        }
        long iat = issuedAt.getTime() / 1000;
        return redis.opsForValue().get(com.project.common.security.SessionRevocation.cutoffKey(uid))
                .map(cutoff -> com.project.common.security.SessionRevocation.allowed(cutoff, iat))
                .defaultIfEmpty(true)
                .timeout(sessionCutoffTimeout)
                .onErrorResume(e -> {
                    skipped(e instanceof java.util.concurrent.TimeoutException ? "timeout" : "error", uid, e);
                    return Mono.just(true);
                });
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
        // [JA] 設定のリフレッシュに追従する。検査と代入がアトミックでないため同時に二度作ることがあるが、同じ値なので無害である。
        String source = secretKey + "\u0000" + jwtSigningKeys + "\u0000" + jwtActiveKid + "\u0000" + jwtAcceptLegacy;
        if (!source.equals(ringSource)) {
            ring = com.project.common.security.JwtKeyRing.of(secretKey, jwtSigningKeys, jwtActiveKid, jwtAcceptLegacy);
            ringSource = source;
        }
        return ring;
    }

    /** 기동 때 한 번 — 키 설정이 틀렸으면 뜨지 않는다(검증만 하는 쪽도 모르는 키로 서명된 토큰을 받게 두지 않는다). */
    // [JA] 起動時に一度 — キー設定が誤っていれば起動しない(検証だけする側も、知らないキーで署名されたトークンを受け入れさせない)。
    @jakarta.annotation.PostConstruct
    void checkKeyRing() {
        ring();
    }

    /** 토큰 머리의 kid 로 검증 키를 고른다 — kid 가 없으면 옛 토큰(legacy), 모르는 kid 는 거절(docs/68 §17). */
    // [JA] トークンヘッダーの kid で検証キーを選ぶ — kid がなければ古いトークン(legacy)、知らない kid は拒否(docs/68 §17)。
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
    /** RewritePath 전의 요청 주소 — 없으면(라우트 밖 시험) 지금 주소. [JA] RewritePath 前の要求アドレス。 */
    static java.net.URI originalUri(ServerWebExchange exchange) {
        java.util.Set<java.net.URI> originals = exchange.getAttribute(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originals != null && !originals.isEmpty()) {
            return originals.iterator().next();
        }
        return exchange.getRequest().getURI();
    }

    /** 데모 계정이 막힌 동작을 하면 403 과 요청 언어의 안내. [JA] デモアカウントが止められた操作をすると 403 と案内。 */
    private Mono<Void> demoForbidden(ServerWebExchange exchange) {
        log.debug("403 — demo account ({})", exchange.getRequest().getPath().value());
        meters.counter(DEMO_BLOCKED_METRIC).increment();
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        String message = GatewayText.pick(exchange,
                "데모 계정에서는 사용할 수 없는 기능입니다.", "デモアカウントではご利用いただけない機能です。");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(message.getBytes(StandardCharsets.UTF_8))));
    }

    // [JA] 401 とリクエスト言語の文言。ログは一行・DEBUG — 期限切れ・未ログインは正常な流れ。トークン・身元は残さない。
    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason, String message) {
        log.debug("401 — {} ({})", reason, exchange.getRequest().getPath().value());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(message.getBytes(StandardCharsets.UTF_8))));
    }
}
