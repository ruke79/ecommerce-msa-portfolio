package com.project.userservice.security.oauth2.repository;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.client.jackson2.OAuth2ClientJackson2Module;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 진행 중인 인가 요청을 <b>Redis</b>에 보관한다. (기본값인 HttpSession 대신)
 *
 * <p>── 왜 바꾸는가 ─────────────────────────────────────────────────────
 *
 * <p>소셜 로그인은 두 번의 요청으로 이루어진다. ① 브라우저가
 * {@code /oauth2/authorization/google} 로 들어오면 서버가 {@code state}(그리고 PKCE 를 켜면
 * {@code code_verifier})를 만들어 <b>저장</b>하고 프로바이더로 보낸다. ② 프로바이더가
 * {@code /login/oauth2/code/google} 로 돌려보내면 서버가 그 값을 <b>꺼내</b> 검증하고
 * 토큰을 교환한다.
 *
 * <p>기본 구현({@code HttpSessionOAuth2AuthorizationRequestRepository})은 그 사이 값을
 * HTTP 세션에 넣는다. 이 서비스에서는 그게 두 가지 이유로 맞지 않는다:
 *
 * <ul>
 *   <li><b>레플리카가 2개다.</b> HPA 의 minReplicas 가 2이고(k8s/70-autoscaling.yaml)
 *       세션 복제도, 세션 어피니티도 없다. ①을 받은 파드와 ②를 받은 파드가 다르면
 *       세션에 아무것도 없어서 {@code authorization_request_not_found} 로 로그인이 깨진다.
 *       확률은 대략 반이다.
 *   <li><b>세션 정책이 STATELESS 다.</b> 이 서비스는 JWT 로 상태를 들고 다니는 설계라
 *       세션을 쓰지 않기로 해 놓고, 소셜 로그인 경로에서만 서블릿 세션이 생기고 있었다 —
 *       설계와 실제가 어긋난 상태였다.
 * </ul>
 *
 * <p>Redis 는 이미 이 서비스가 리프레시 토큰에 쓰고 있다(RefreshTokenRepository).
 * 새 인프라가 늘지 않는다.
 *
 * <p>── 왜 쿠키가 아니라 Redis 인가 ────────────────────────────────────
 *
 * <p>흔한 대안은 인가 요청을 통째로 쿠키에 담는 것이다. 동작은 하지만 PKCE 를 켠 지금은
 * <b>{@code code_verifier} 가 브라우저로 나가게 된다.</b> PKCE 의 전제는 "verifier 를 아는
 * 쪽만 코드를 교환할 수 있다"인데, 그 값을 클라이언트에 맡기면 보호의 상당 부분이 사라진다
 * (HttpOnly 쿠키라도 XSS·확장프로그램·브라우저 취약점의 사정권에 있다). 이 애플리케이션은
 * 클라이언트 시크릿을 지킬 수 있는 <b>기밀 클라이언트</b>이므로 verifier 도 서버에 둔다.
 *
 * <p>── 한 번만 쓰인다 ────────────────────────────────────────────────
 *
 * <p>꺼낼 때 {@code GETDEL}(원자적)을 쓴다. 콜백이 두 번 도착해도 두 번째는 값을 얻지
 * 못한다. GET 후 DELETE 로 나누면 그 사이에 두 요청이 같은 인가 요청을 얻어 코드 재사용
 * 창이 열린다 — 짧지만 실재하는 창이다.
 *
 * <p>[JA] 進行中の認可リクエストを Redis に保管する(既定の HttpSession の代わりに)。
 * なぜ変えるか: ソーシャルログインは 2 回のリクエストで成り立つ。① ブラウザが {@code /oauth2/authorization/google} に来ると
 * サーバーが {@code state}(PKCE を有効にすれば {@code code_verifier} も)を作って保存し、プロバイダーへ送る。② プロバイダーが
 * {@code /login/oauth2/code/google} に戻すと、サーバーがその値を取り出して検証しトークンを交換する。既定の実装
 * ({@code HttpSessionOAuth2AuthorizationRequestRepository})はその間の値を HTTP セッションに入れる。このサービスでは二つの理由で
 * それが合わない: レプリカが 2 つある — HPA の minReplicas が 2 で(k8s/70-autoscaling.yaml)セッション複製もセッション
 * アフィニティもない。①を受けたポッドと②を受けたポッドが違うとセッションに何もなく、{@code authorization_request_not_found}
 * でログインが壊れる。確率はおよそ半分である。セッションポリシーが STATELESS である — JWT で状態を持ち回る設計でセッションを
 * 使わないと決めておきながら、ソーシャルログイン経路でだけサーブレットセッションが生まれていた — 設計と実際が食い違っていた。
 * Redis はすでにこのサービスがリフレッシュトークンに使っている(RefreshTokenRepository)。新しいインフラは増えない。
 * なぜ Cookie ではなく Redis か: よくある代替は認可リクエストを丸ごと Cookie に入れることである。動きはするが、PKCE を有効にした
 * 今は {@code code_verifier} がブラウザへ出てしまう。PKCE の前提は「verifier を知る側だけがコードを交換できる」ことなのに、
 * その値をクライアントに預けると保護のかなりの部分が失われる(HttpOnly Cookie でも XSS・拡張機能・ブラウザ脆弱性の射程内に
 * ある)。このアプリケーションはクライアントシークレットを守れる機密クライアントなので、verifier もサーバーに置く。
 * 一度だけ使われる: 取り出すときに {@code GETDEL}(アトミック)を使う。コールバックが二度届いても二度目は値を得られない。GET と
 * DELETE に分けると、その間に二つのリクエストが同じ認可リクエストを得てコード再利用の窓が開く — 短いが実在する窓である。
 */
@Slf4j
@Component
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String KEY_PREFIX = "oauth2:authreq:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisOAuth2AuthorizationRequestRepository(
            StringRedisTemplate redis,
            @Value("${oauth2.authorization-request-ttl-seconds:600}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);

        // ⚠️ 애플리케이션의 공용 ObjectMapper 를 쓰지 않는다.
        //
        // Security 의 믹스인들은 **다형 타입 정보(@class)** 를 켠 상태를 전제로 한다.
        // 공용 매퍼에 그걸 켜면 모든 REST 응답에 @class 가 섞여 나가 API 계약이 깨진다.
        // 그래서 이 용도만의 매퍼를 따로 만든다.
        // [JA] ⚠️ アプリケーション共用の ObjectMapper を使わない。
        //      Security の Mixin は**多態型情報(@class)**を有効にした状態を前提とする。
        //      共用マッパーでそれを有効にするとすべての REST 応答に @class が混ざって出て API 契約が壊れる。
        //      そこでこの用途専用のマッパーを別に作る。
        ObjectMapper om = new ObjectMapper();
        ClassLoader loader = getClass().getClassLoader();
        om.registerModules(SecurityJackson2Modules.getModules(loader));
        om.registerModule(new OAuth2ClientJackson2Module());
        this.mapper = om;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = stateOf(request);
        if (state == null) {
            return null;
        }
        return deserialize(redis.opsForValue().get(key(state)), state);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {

        // 계약상 null 은 "지워라"라는 뜻이다. 이걸 빠뜨리면 로그아웃/실패 경로에서
        // 인가 요청이 TTL 이 끝날 때까지 남는다.
        // [JA] 契約上 null は「消せ」という意味である。これを漏らすとログアウト/失敗の経路で
        //      認可リクエストが TTL が切れるまで残る。
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }

        String state = authorizationRequest.getState();
        if (!StringUtils.hasText(state)) {
            // state 없는 인가 요청은 CSRF 방어가 없다는 뜻이다. 저장을 거부한다.
            // [JA] state のない認可リクエストは CSRF 防御がないという意味である。保存を拒否する。
            throw new IllegalArgumentException("인가 요청에 state 가 없습니다 — 저장할 수 없습니다");
        }

        try {
            redis.opsForValue().set(key(state), mapper.writeValueAsString(authorizationRequest), ttl);
        } catch (Exception e) {
            // 여기서 조용히 넘어가면 콜백에서 "인가 요청을 찾을 수 없다"로 나타나고,
            // 그때는 원인이 Redis 라는 단서가 남지 않는다. 시작 지점에서 죽는 편이 낫다.
            // [JA] ここで静かにやり過ごすとコールバックで「認可リクエストが見つからない」として現れ、
            //      そのときには原因が Redis だという手がかりが残らない。開始地点で落ちる方がよい。
            log.error("인가 요청을 저장하지 못했습니다 — 소셜 로그인을 시작할 수 없습니다", e);
            throw new IllegalStateException("인가 요청 저장 실패", e);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        String state = stateOf(request);
        if (state == null) {
            return null;
        }
        // GETDEL — 읽기와 삭제가 한 번에 일어난다(위 §한 번만 쓰인다).
        // [JA] GETDEL — 読み取りと削除が一度に起きる(上の §一度だけ使われる)。
        return deserialize(redis.opsForValue().getAndDelete(key(state)), state);
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }

    private String stateOf(HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        return StringUtils.hasText(state) ? state : null;
    }

    private OAuth2AuthorizationRequest deserialize(String json, String state) {
        if (json == null) {
            // 정상적인 경우다 — TTL 만료, 이미 쓰인 state, 또는 위조된 콜백.
            // state 값은 **로그에 남기지 않는다**(CSRF 토큰과 같은 성격이다).
            // [JA] 正常な場合である — TTL 切れ、使用済みの state、または偽造されたコールバック。
            //      state の値は**ログに残さない**(CSRF トークンと同じ性質である)。
            log.debug("인가 요청을 찾지 못했습니다 (만료·재사용·위조 중 하나)");
            return null;
        }
        try {
            return mapper.readValue(json, OAuth2AuthorizationRequest.class);
        } catch (Exception e) {
            // 역직렬화가 깨졌다는 것은 저장 형식이 바뀌었다는 뜻이다(스프링 업그레이드 등).
            // 남은 값을 지워서 같은 사용자가 계속 같은 오류를 겪지 않게 한다.
            // [JA] デシリアライズが壊れたということは保存形式が変わったという意味である(Spring のアップグレードなど)。
            //      残った値を消して、同じユーザーが同じエラーを繰り返さないようにする。
            log.warn("인가 요청 역직렬화 실패 — 해당 항목을 폐기합니다", e);
            redis.delete(key(state));
            return null;
        }
    }
}
