package com.project.userservice.security.oauth2;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 소셜 로그인 프로바이더 등록 — 설정이 있는 것만.
 *
 * <p>예전 버전은 clientId("아이디")·clientSecret("비밀번호") 가 하드코딩된 채였고
 * redirect URI 도 localhost 였다 — 튜토리얼을 옮기다 만 상태라 어떤 환경에서도 동작할
 * 수 없었다. 지금은:
 *
 * <ul>
 *   <li>자격증명은 Secret 에서 온다(OAUTH2_*_CLIENT_ID/SECRET). <b>비어 있는 프로바이더는
 *       등록하지 않는다</b> — 프론트엔드의 버튼도 함께 사라진다(/api/auth/public/oauth2/providers)
 *   <li>redirect URI 는 공개 주소로 만든다:
 *       {@code {app.public-url}/user-service/login/oauth2/code/{provider}}.
 *       프로바이더 콘솔에 등록하는 값과 <b>정확히 같아야 한다</b> — 한 글자라도 다르면
 *       redirect_uri_mismatch 로 로그인 화면에서 튕긴다
 *   <li>엔드포인트 주소는 스프링의 {@link CommonOAuth2Provider} 를 쓴다. 구글·깃허브·
 *       페이스북의 authorize/token/userinfo 주소를 손으로 적으면 언젠가 낡는다.
 *       카카오·LINE 은 그 목록에 없어 직접 적는다({@link #kakao}, {@link #line})
 * </ul>
 *
 * <p>[JA] ソーシャルログインのプロバイダ登録 — 設定があるものだけ。
 *
 * <p>以前のバージョンは clientId("아이디")・clientSecret("비밀번호") がハードコードされたままで、
 * redirect URI も localhost だった — チュートリアルを移植しかけた状態で、どの環境でも動作し得なかった。
 * 今は:
 *
 * <ul>
 *   <li>認証情報は Secret から来る(OAUTH2_*_CLIENT_ID/SECRET)。<b>空のプロバイダは
 *       登録しない</b> — フロントエンドのボタンも一緒に消える(/api/auth/public/oauth2/providers)
 *   <li>redirect URI は公開アドレスで組み立てる:
 *       {@code {app.public-url}/user-service/login/oauth2/code/{provider}}.
 *       プロバイダのコンソールに登録する値と<b>完全に一致しなければならない</b> — 一文字でも違うと
 *       redirect_uri_mismatch でログイン画面から弾かれる
 *   <li>エンドポイントのアドレスは Spring の {@link CommonOAuth2Provider} を使う。Google・GitHub・
 *       Facebook の authorize/token/userinfo アドレスを手書きすると、いつか古くなる。
 *       Kakao・LINE はその一覧に無いので直接書く({@link #kakao}, {@link #line})
 * </ul>
 */
@Slf4j
@Component
public class SocialClientRegistration {

    @Value("${app.public-url}")
    private String publicUrl;

    @Value("${oauth2.google.client-id:}")
    private String googleClientId;
    @Value("${oauth2.google.client-secret:}")
    private String googleClientSecret;

    @Value("${oauth2.github.client-id:}")
    private String githubClientId;
    @Value("${oauth2.github.client-secret:}")
    private String githubClientSecret;

    @Value("${oauth2.facebook.client-id:}")
    private String facebookClientId;
    @Value("${oauth2.facebook.client-secret:}")
    private String facebookClientSecret;

    @Value("${oauth2.kakao.client-id:}")
    private String kakaoClientId;
    @Value("${oauth2.kakao.client-secret:}")
    private String kakaoClientSecret;

    @Value("${oauth2.line.client-id:}")
    private String lineClientId;
    @Value("${oauth2.line.client-secret:}")
    private String lineClientSecret;

    /**
     * 게이트웨이 경유 콜백 주소. {baseUrl} 템플릿을 쓰지 않고 절대 주소로 굳힌다 —
     * 서비스는 게이트웨이 뒤(RewritePath로 /user-service 접두사가 벗겨진 채)에 있어서,
     * 서블릿이 스스로 계산한 baseUrl 은 외부에서 접근 불가능한 내부 주소가 된다.
     *
     * <p>[JA] ゲートウェイ経由のコールバックアドレス。{baseUrl} テンプレートは使わず絶対アドレスで固定する —
     * サービスはゲートウェイの背後(RewritePath で /user-service プレフィックスが剥がされた状態)にあるため、
     * サーブレットが自ら算出した baseUrl は外部からアクセスできない内部アドレスになってしまう。
     */
    private String redirectUri(String provider) {
        return publicUrl + "/user-service/login/oauth2/code/" + provider;
    }

    public List<ClientRegistration> configuredRegistrations() {
        List<ClientRegistration> list = new ArrayList<>();

        if (!googleClientId.isBlank() && !googleClientSecret.isBlank()) {
            list.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .redirectUri(redirectUri("google"))
                    .build());
        }
        if (!githubClientId.isBlank() && !githubClientSecret.isBlank()) {
            list.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(githubClientId)
                    .clientSecret(githubClientSecret)
                    // 기본 scope 는 read:user 뿐이다. 이메일이 비공개인 계정(다수다)은
                    // user 속성에 email 이 null 로 오므로, /user/emails 를 읽을 권한을
                    // 함께 받는다 — CustomOAuth2UserService 가 그 폴백을 수행한다.
                    // [JA] デフォルトの scope は read:user のみである。メールアドレスが非公開のアカウント(多数派だ)は
                    //      user 属性の email が null で来るので、/user/emails を読む権限も併せて取得する —
                    //      CustomOAuth2UserService がそのフォールバックを行う。
                    .scope("read:user", "user:email")
                    .redirectUri(redirectUri("github"))
                    .build());
        }
        if (!facebookClientId.isBlank() && !facebookClientSecret.isBlank()) {
            list.add(CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                    .clientId(facebookClientId)
                    .clientSecret(facebookClientSecret)
                    .redirectUri(redirectUri("facebook"))
                    .build());
        }

        if (!kakaoClientId.isBlank() && !kakaoClientSecret.isBlank()) {
            list.add(kakao(kakaoClientId, kakaoClientSecret, redirectUri("kakao")));
        }
        if (!lineClientId.isBlank() && !lineClientSecret.isBlank()) {
            list.add(line(lineClientId, lineClientSecret, redirectUri("line")));
        }

        if (list.isEmpty()) {
            log.info("소셜 로그인: 설정된 프로바이더가 없습니다 (OAUTH2_*_CLIENT_ID 미설정) — 버튼이 표시되지 않습니다.");
        } else {
            log.info("소셜 로그인 프로바이더: {}",
                    list.stream().map(ClientRegistration::getRegistrationId).toList());
        }
        return list;
    }

    /**
     * 카카오 — 스프링의 {@link CommonOAuth2Provider} 에 없어서 주소를 직접 적는다(카카오 로그인 REST API 문서의 값).
     *
     * <ul>
     *   <li>client-id 는 앱의 <b>REST API 키</b>다. 시크릿은 [보안] 의 Client Secret 을 "사용함" 으로 켠 값 — 켜 두면 토큰
     *       요청에 반드시 실려야 하고, 카카오는 본문(form)으로 받는다({@code client_secret_post})
     *   <li>scope 는 동의항목 ID 다. 이메일({@code account_email})이 없으면 이 저장소는 계정을 만들 수 없다
     *       (CustomOAuth2UserService 가 email_required 로 거절). 콘솔에서 이메일 동의항목을 켜야 한다
     *   <li>사용자 식별자는 응답 최상위의 {@code id}(숫자)다
     * </ul>
     *
     * <p>[JA] Kakao — Spring の {@link CommonOAuth2Provider} に無いのでアドレスを直接書く(Kakao ログイン REST API
     * ドキュメントの値)。
     *
     * <ul>
     *   <li>client-id はアプリの <b>REST API キー</b>である。シークレットは [보안](セキュリティ)の Client Secret を
     *       "사용함"(使用する)にした値 — 有効にするとトークンリクエストに必ず載せる必要があり、Kakao は
     *       本文(form)で受け取る({@code client_secret_post})
     *   <li>scope は同意項目 ID である。メール({@code account_email})が無いとこのリポジトリはアカウントを作れない
     *       (CustomOAuth2UserService が email_required で拒否する)。コンソールでメールの同意項目を有効にする必要がある
     *   <li>ユーザー識別子はレスポンス最上位の {@code id}(数値)である
     * </ul>
     */
    static ClientRegistration kakao(String clientId, String clientSecret, String redirectUri) {
        return ClientRegistration.withRegistrationId("kakao")
                .clientName("Kakao")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("profile_nickname", "account_email")
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userNameAttributeName("id")
                .build();
    }

    /** LINE ID 토큰 발급자 — 스프링이 ID 토큰의 {@code iss} 를 이 값과 대조한다. */
    // [JA] LINE ID トークンの発行者 — Spring は ID トークンの {@code iss} をこの値と照合する。
    public static final String LINE_ISSUER = "https://access.line.me";

    /**
     * LINE 로그인 v2.1 — <b>OIDC</b> 로 등록한다({@code openid} scope).
     *
     * <ul>
     *   <li>이메일은 ID 토큰에만 실린다(userinfo·프로필 API 에는 없다). 그래서 OAuth2 가 아니라 OIDC 여야 하고,
     *       채널 콘솔에서 <b>이메일 주소 권한</b>을 신청해 승인받아야 한다 — 없으면 모든 LINE 로그인이 email_required
     *   <li>client-id 는 채널 ID, 시크릿은 채널 시크릿. 토큰 요청에 본문(form)으로 싣는다({@code client_secret_post})
     *   <li>웹 로그인의 ID 토큰은 <b>ES256</b> 서명이다 — 스프링 기본(RS256)으로는 검증이 실패한다.
     *       알고리즘은 {@code SecurityConfig#idTokenDecoderFactory} 가 등록 id 로 고른다
     * </ul>
     *
     * <p>[JA] LINE ログイン v2.1 — <b>OIDC</b> として登録する({@code openid} scope)。
     *
     * <ul>
     *   <li>メールアドレスは ID トークンにしか載らない(userinfo・プロフィール API には無い)。だから OAuth2 ではなく
     *       OIDC でなければならず、チャネルのコンソールで<b>メールアドレス取得権限</b>を申請して承認を受ける必要がある
     *       — 無ければすべての LINE ログインが email_required になる
     *   <li>client-id はチャネル ID、シークレットはチャネルシークレット。トークンリクエストに本文(form)で載せる
     *       ({@code client_secret_post})
     *   <li>ウェブログインの ID トークンは <b>ES256</b> 署名である — Spring のデフォルト(RS256)では検証に失敗する。
     *       アルゴリズムは {@code SecurityConfig#idTokenDecoderFactory} が登録 id で選ぶ
     * </ul>
     */
    static ClientRegistration line(String clientId, String clientSecret, String redirectUri) {
        return ClientRegistration.withRegistrationId("line")
                .clientName("LINE")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "profile", "email")
                .authorizationUri("https://access.line.me/oauth2/v2.1/authorize")
                .tokenUri("https://api.line.me/oauth2/v2.1/token")
                .userInfoUri("https://api.line.me/oauth2/v2.1/userinfo")
                .jwkSetUri("https://api.line.me/oauth2/v2.1/certs")
                .issuerUri(LINE_ISSUER)
                .userNameAttributeName("sub")
                .build();
    }
}
