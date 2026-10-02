package com.project.gatewayserver.filter;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;

import io.jsonwebtoken.UnsupportedJwtException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import com.project.common.constants.TokenStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
@Component
public class JwtAuthorizationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthorizationGatewayFilterFactory.Config> {

    // Environment env;

    public static class Config {
        private List<String> exceptPaths;

        public List<String> getExceptPaths() {
            return exceptPaths;
        }

        public void setExceptPaths(List<String> exceptPaths) {
            this.exceptPaths = exceptPaths;
        }
    }

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
        // this.env = env;
    }

    @Override
    public GatewayFilter apply(Config config) {

        // return new OrderedGatewayFilter(
        return (exchange, chain) -> {

            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();
            // WebSocket 관련 경로를 필터 검사에서 제외
            // [JA] WebSocket 関連のパスをフィルター検査から除外
            log.info("JwtAuthorizationGatewayFilterFactory called for path: {}", request.getURI().getPath());

            // 예외 경로 목록에 현재 경로가 포함되어 있는지 확인
            // [JA] 例外パスのリストに現在のパスが含まれているか確認
            if (config.getExceptPaths() != null) {
                log.info("Except paths: {}", config.getExceptPaths());
                boolean isExcepted = config.getExceptPaths().stream().anyMatch(path::startsWith);
                log.info("Path {} matches exceptPaths: {}", path, isExcepted);

                if (isExcepted) {
                    log.info("Path {} is an excepted path, bypassing JWT check.", path);
                    return chain.filter(exchange);
                }
            }

            String jwt = null;
            // 1. HttpHeaders.AUTHORIZATION 에서 토큰 추출
            // [JA] 1. HttpHeaders.AUTHORIZATION からトークンを抽出
            if (request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                String authorizationHeader = request.getHeaders().get(HttpHeaders.AUTHORIZATION).get(0);
                if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                    jwt = authorizationHeader.replace("Bearer ", "");
                }
            }
            
            // 2. Authorization 헤더에 토큰이 없으면, 쿠키에서 ACCESS 토큰 추출
            // [JA] 2. Authorization ヘッダーにトークンがなければ、Cookie から ACCESS トークンを抽出
            if (jwt == null) {
                HttpCookie accessCookie = request.getCookies().getFirst("ACCESS");
                if (accessCookie != null) {
                    jwt = accessCookie.getValue();
                }
            }

            if (jwt == null) {
                return OnError(exchange, com.project.gatewayserver.i18n.GatewayText.pick(exchange,
                        "로그인이 필요한 서비스입니다.", "ログインが必要なサービスです。"), HttpStatus.UNAUTHORIZED);
            }

            

            try {

                isJwtTokenExpired(jwt);

            } catch (ExpiredJwtException e) {
                return OnError(exchange, "access token expired", HttpStatus.UNAUTHORIZED);
            } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
                // 🐛 예전: 서명이 안 맞는 토큰 · 모르는 kid 는 예외가 그대로 올라가 500 이었다. 화면은 401 이어야 리프레시로 새 토큰을
                //    받는다 — 키를 은퇴시킨 직후(docs/68 §17) 옛 키의 토큰을 든 사용자가 500 을 보게 된다. 종류만 남긴다(토큰은 남기지 않는다).
                // [JA] 以前は署名不一致 · 未知の kid の例外がそのまま上がり 500 だった。401 なら画面がリフレッシュで新しいトークンを受け取る。
                log.info("유효하지 않은 토큰 — 401 ({})", e.getClass().getSimpleName());
                return OnError(exchange, "invalid access token", HttpStatus.UNAUTHORIZED);
            }

            TokenStatus tokenStatus = validateJwtToken(jwt);

            // 🔒 액세스 토큰만 신원으로 받는다. 예전에는 서명·만료만 봐서 14일짜리 리프레시 토큰(같은 키로 서명)이 Bearer 로
            //    통했고, 로그아웃 뒤에도 그랬다(user-service JwtUtils.TOKEN_TYPE_CLAIM). 종류 클레임이 없는 옛 액세스 토큰도
            //    여기서 한 번 401 — 화면은 401 이면 리프레시로 새 토큰을 받는다.
            // [JA] 🔒 アクセストークンだけを身元として受け付ける。以前は署名・有効期限しか見ていなかったため、14日間有効の
            //      リフレッシュトークン(同じ鍵で署名)が Bearer として通り、ログアウト後もそうだった
            //      (user-service JwtUtils.TOKEN_TYPE_CLAIM)。種別クレームのない旧アクセストークンもここで一度 401 になる
            //      — 画面は 401 ならリフレッシュで新しいトークンを受け取る。
            if (tokenStatus == TokenStatus.VALID && !isAccessToken(jwt)) {
                log.info("액세스 토큰이 아닌 토큰(리프레시 또는 옛 토큰) — 401. path={}", path);
                return OnError(exchange, "invalid access token", HttpStatus.UNAUTHORIZED);
            }

            if (tokenStatus == TokenStatus.VALID) {

                // 검증된 토큰에서만 신원을 뽑는다.
                //
                // 예전에는 subject(이메일)를 그대로 "User-Id" 로 넘겼다. 이름과 내용이
                // 어긋나 있어서, 이 헤더를 숫자 id 로 받는 서비스는 전부 400 이 났다.
                // 이제 둘을 나눈다: User-Id 는 불변 숫자 id, User-Email 은 이메일.
                // [JA] 検証済みのトークンからだけ身元を取り出す。
                //
                //      以前は subject(メールアドレス)をそのまま "User-Id" として渡していた。名前と中身が
                //      食い違っていたため、このヘッダーを数値 id として受け取るサービスはすべて 400 になっていた。
                //      今は二つを分ける: User-Id は不変の数値 id、User-Email はメールアドレス。
                String email = extractSubjectFromJwt(jwt);
                Long uid = extractUidFromJwt(jwt);
                String role = extractRoleFromJwt(jwt);

                ServerHttpRequest.Builder mutated = request.mutate()
                        // ⚠️ 클라이언트가 직접 보낸 값을 반드시 지운다. mutate().header(...)
                        //    는 값을 덮어쓰지만, 값이 없어 아래에서 세팅을 건너뛰는 경우
                        //    클라이언트가 보낸 헤더가 그대로 하위 서비스에 전달된다 —
                        //    그러면 헤더 하나로 남의 계정이나 관리자를 사칭할 수 있다.
                        //    특히 User-Role 은 지우지 않으면 권한 상승으로 직결된다.
                        // [JA] ⚠️ クライアントが直接送った値を必ず消す。mutate().header(...)
                        //      は値を上書きするが、値がなくて下でのセットをスキップする場合、
                        //      クライアントが送ったヘッダーがそのまま下流サービスに渡される —
                        //      そうなるとヘッダー一つで他人のアカウントや管理者になりすませる。
                        //      特に User-Role は消さなければ権限昇格に直結する。
                        .headers(h -> {
                            h.remove("User-Id");
                            h.remove("User-Email");
                            h.remove("User-Role");
                        });

                if (email != null) {
                    mutated.header("User-Email", email);
                }
                if (role != null) {
                    mutated.header("User-Role", role);
                }
                if (uid != null) {
                    mutated.header("User-Id", String.valueOf(uid));
                } else {
                    // uid 클레임이 없는 토큰이다(이 변경 이전에 발급된 토큰, 또는 OAuth
                    // 경로). 신원을 확인할 수 없으므로 통과시키지 않는다 — 헤더를 비운 채
                    // 넘기면 하위 서비스가 "인증 안 된 요청"으로 처리하겠지만, 여기서
                    // 명확히 끊는 편이 낫다. 사용자는 다시 로그인하면 새 토큰을 받는다.
                    // [JA] uid クレームのないトークンである(この変更以前に発行されたトークン、または OAuth
                    //      経路)。身元を確認できないので通さない — ヘッダーを空にしたまま
                    //      渡せば下流サービスが「認証されていないリクエスト」として処理するだろうが、ここで
                    //      明確に遮断する方がよい。ユーザーは再ログインすれば新しいトークンを受け取る。
                    log.warn("uid 클레임이 없는 토큰 — 재로그인이 필요하다. path={}", path);
                    return OnError(exchange, "please sign in again", HttpStatus.UNAUTHORIZED);
                }

                return chain.filter(exchange.mutate().request(mutated.build()).build());
            }

            return OnError(exchange, "invalid access token", HttpStatus.UNAUTHORIZED);
        };
        // , Ordered.LOWEST_PRECEDENCE);
    }

    static final String TOKEN_TYPE_CLAIM = "tokenType";
    static final String ACCESS = "access";

    /** 서명을 검증한 뒤 종류 클레임이 {@code access} 인가. 없으면(리프레시·옛 토큰) 아니다. */
    // [JA] 署名を検証したうえで種別クレームが {@code access} か。なければ(リフレッシュ・旧トークン)違う。
    boolean isAccessToken(String token) {
        try {
            return ACCESS.equals(Jwts.parser().keyLocator(keyLocator()).build()
                    .parseSignedClaims(token).getPayload().get(TOKEN_TYPE_CLAIM, String.class));
        } catch (RuntimeException e) {
            return false;
        }
    }

    com.project.common.security.JwtKeyRing ring() {
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

    /** 토큰의 subject — 이메일이다. 서명을 검증한 뒤에만 읽는다. */
    // [JA] トークンの subject — メールアドレスである。署名を検証した後にだけ読む。
    private String extractSubjectFromJwt(String token) {
        try {
            return Jwts.parser()
                    .keyLocator(keyLocator())
                    .build().parseSignedClaims(token)
                    .getPayload().getSubject();

        } catch (Exception e) {
            log.error("Failed to extract subject from JWT", e);
            return null;
        }
    }

    /** 역할({@code role} 클레임). 하위 서비스의 관리자 전용 엔드포인트가 이걸 본다. */
    // [JA] ロール({@code role} クレーム)。下流サービスの管理者専用エンドポイントがこれを見る。
    private String extractRoleFromJwt(String token) {
        try {
            Object role = Jwts.parser()
                    .keyLocator(keyLocator())
                    .build().parseSignedClaims(token)
                    .getPayload().get("role");

            return role == null ? null : role.toString();

        } catch (Exception e) {
            log.error("Failed to extract role from JWT", e);
            return null;
        }
    }

    /**
     * 불변 숫자 사용자 id({@code uid} 클레임).
     *
     * <p>소유권 판정의 기준이다. 이메일은 바뀔 수 있어 식별자로 쓰면 안 된다.
     *
     * <p>[JA] 不変の数値ユーザー id({@code uid} クレーム)。
     *
     * <p>所有権判定の基準である。メールアドレスは変わりうるので識別子として使ってはならない。
     */
    private Long extractUidFromJwt(String token) {
        try {
            Object uid = Jwts.parser()
                    .keyLocator(keyLocator())
                    .build().parseSignedClaims(token)
                    .getPayload().get("uid");

            if (uid == null) {
                return null;
            }
            // jjwt 는 JSON 숫자를 Integer 또는 Long 으로 준다. 둘 다 받는다.
            // [JA] jjwt は JSON の数値を Integer または Long で返す。両方を受け付ける。
            return uid instanceof Number n ? n.longValue() : Long.parseLong(uid.toString());

        } catch (Exception e) {
            log.error("Failed to extract uid from JWT", e);
            return null;
        }
    }

    // private boolean isJwtValid(String jwt) {
    // boolean returnValue = true;
    // String id = null;

    // try {
    // //복호화
    // Algorithm algorithm = Algorithm.HMAC256(secretKey.getBytes());
    // JWTVerifier verifier = JWT.require(algorithm).build();

    // DecodedJWT decodedJWT = verifier.verify(jwt);
    // id = decodedJWT.getSubject();
    // } catch (Exception e) {
    // returnValue = false;
    // }
    // if (id == null || id.isEmpty()) {
    // returnValue = false;
    // }

    // return returnValue;
    // }

    private boolean isJwtTokenExpired(String token) {

        return Jwts.parser()
                .keyLocator(keyLocator())
                .build().parseSignedClaims(token)
                .getPayload().getExpiration().before(new Date());
    }

    public TokenStatus validateJwtToken(String authToken) {
        try {
            Jwts.parser().keyLocator(keyLocator())
                    .build().parseSignedClaims(authToken);
            return TokenStatus.VALID;
        } catch (MalformedJwtException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
            return TokenStatus.INVALID;
        } catch (ExpiredJwtException e) {
            log.error("JWT token is expired: {}", e.getMessage());
            return TokenStatus.EXPIRED;
        } catch (UnsupportedJwtException e) {
            log.error("JWT token is unsupported: {}", e.getMessage());
            return TokenStatus.UNSUPPORTED;
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
            return TokenStatus.ILLEGAL_ARGS;
        }
    }

    // Mono, Flux => Spring WebFlux
    private Mono<Void> OnError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        log.error(err);
        // 문자열로 에러 메시지 생성
        // [JA] 文字列でエラーメッセージを生成
        String errorMessage = err;
        byte[] bytes = errorMessage.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = new DefaultDataBufferFactory().wrap(bytes);

        // 컨텐트 타입 설정
        // [JA] コンテントタイプを設定
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);

        // 에러 로깅
        // [JA] エラーのロギング
        log.error(err);

        // 에러 메시지를 응답 본문에 쓰기
        // [JA] エラーメッセージをレスポンスボディに書き込む
        return response.writeWith(Flux.just(buffer));
    }

}
