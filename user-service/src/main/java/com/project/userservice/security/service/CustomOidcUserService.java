package com.project.userservice.security.service;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.project.userservice.dto.CustomOAuth2User;
import com.project.userservice.dto.CustomOidcUser;

/**
 * OIDC 소셜 로그인(구글·LINE)의 사용자 적재.
 *
 * <p>🐛 {@code openid} scope 가 있는 등록은 스프링이 <b>OAuth2 가 아니라 OIDC 경로</b>로 처리한다 — 그 경로는
 * {@code userInfoEndpoint().userService(...)} 가 아니라 {@code oidcUserService(...)} 를 부른다. 구글 등록
 * ({@code CommonOAuth2Provider.GOOGLE})은 scope 에 openid 가 들어 있는데 OIDC 쪽을 설정하지 않아서,
 * {@link CustomOAuth2UserService} 가 <b>한 번도 불리지 않았다</b>: 새 사용자는 만들어지지 않고, 기본 서비스가 돌려준
 * {@code DefaultOidcUser} 를 성공 핸들러가 {@code CustomOAuth2User} 로 형 변환하다 실패했다. 운영에서 구글을 켠 적이
 * 없어 드러나지 않았다.
 *
 * <p>ID 토큰 검증(서명·iss·aud·exp·nonce)과 userinfo 조회는 스프링의 {@link OidcUserService} 에 맡기고, 계정 연결은
 * OAuth2 경로와 같은 {@link CustomOAuth2UserService#resolve} 로 한다 — 규칙이 두 벌이 되지 않게.
 *
 * <p>[JA] OIDC ソーシャルログイン(Google・LINE)のユーザー読み込み。
 *
 * <p>🐛 {@code openid} scope を持つ登録を、Spring は<b>OAuth2 ではなく OIDC の経路</b>で処理する — その経路は
 * {@code userInfoEndpoint().userService(...)} ではなく {@code oidcUserService(...)} を呼ぶ。Google の登録
 * ({@code CommonOAuth2Provider.GOOGLE})は scope に openid が含まれているのに OIDC 側を設定していなかったため、
 * {@link CustomOAuth2UserService} が<b>一度も呼ばれなかった</b>: 新規ユーザーは作られず、デフォルトのサービスが返した
 * {@code DefaultOidcUser} を成功ハンドラが {@code CustomOAuth2User} にキャストしようとして失敗していた。本番で Google を
 * 有効にしたことが無かったので表面化しなかった。
 *
 * <p>ID トークンの検証(署名・iss・aud・exp・nonce)と userinfo の取得は Spring の {@link OidcUserService} に任せ、
 * アカウント連携は OAuth2 経路と同じ {@link CustomOAuth2UserService#resolve} で行う — ルールが二重にならないように。
 */
@Service
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final CustomOAuth2UserService accounts;

    /** 스프링의 검증 경로. 테스트가 바꿔 끼울 수 있게 필드로 둔다. */
    // [JA] Spring の検証経路。テストで差し替えられるようフィールドにしておく。
    OAuth2UserService<OidcUserRequest, OidcUser> delegate = new OidcUserService();

    public CustomOidcUserService(CustomOAuth2UserService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser verified = delegate.loadUser(userRequest);
        CustomOAuth2User resolved = accounts.resolve(userRequest.getClientRegistration().getRegistrationId(),
                verified.getClaims(), userRequest.getAccessToken().getTokenValue());
        return new CustomOidcUser(resolved, verified);
    }
}
