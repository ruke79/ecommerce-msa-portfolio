package com.project.userservice.handler;

import java.io.IOException;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 소셜 로그인 실패 — 프론트엔드 로그인 화면으로 <b>이유 코드</b>를 들고 돌려보낸다.
 *
 * <p>🐛 예전에는 실패 핸들러가 없었다. 스프링 기본값은 {@code loginPage + "?error"} — 값 없는 {@code error} 라 화면은
 * "소셜 로그인에 실패했습니다" 한 줄만 띄웠다. 이메일 동의를 빼먹은 사람도, 동의 화면에서 취소한 사람도, 인증 안 된
 * 이메일인 사람도 같은 문구를 봤다 — 스스로 고칠 수 있는 실패인데 무엇을 고쳐야 하는지 몰랐다. 카카오는 이메일이
 * 선택 동의일 수 있어서 이 실패가 흔하다.
 *
 * <p>코드는 <b>허용 목록</b>만 그대로 보낸다. 나머지는 {@code oauth_failed} — 프로바이더 오류 설명(description)이나
 * 예외 문구는 URL 에 싣지 않는다(내부 사정이 새고, 주소창·히스토리에 남는다).
 *
 * <p>[JA] ソーシャルログイン失敗 — フロントエンドのログイン画面へ<b>理由コード</b>を持たせて戻す。
 *
 * <p>🐛 以前は失敗ハンドラが無かった。Spring のデフォルトは {@code loginPage + "?error"} — 値の無い {@code error} なので、
 * 画面は "소셜 로그인에 실패했습니다"(ソーシャルログインに失敗しました)の一行しか表示しなかった。メール同意を
 * 外した人も、同意画面でキャンセルした人も、未検証のメールの人も同じ文言を見た — 自分で直せる失敗なのに、
 * 何を直せばよいか分からなかった。Kakao はメールが任意同意になり得るので、この失敗はよくある。
 *
 * <p>コードは<b>許可リスト</b>にあるものだけをそのまま送る。それ以外は {@code oauth_failed} — プロバイダのエラー説明
 * (description)や例外メッセージは URL に載せない(内部事情が漏れ、アドレスバー・履歴に残る)。
 */
@Slf4j
@Component
public class OAuth2FailureHandler implements AuthenticationFailureHandler {

    /** 화면이 따로 안내하는 코드 — SignInPage 의 OAUTH_ERROR_KEYS 와 짝이다. */
    // [JA] 画面が個別に案内するコード — SignInPage の OAUTH_ERROR_KEYS と対になる。
    static final Set<String> SHOWN_CODES = Set.of(
            "email_required",     // 프로바이더가 이메일을 주지 않았다(동의 안 함 · 비공개) ／ [JA] プロバイダがメールを渡さなかった(未同意・非公開)
            "unverified_email",   // 이메일이 인증되지 않았다 ／ [JA] メールが検証されていない
            "access_denied");     // 동의 화면에서 취소했다 ／ [JA] 同意画面でキャンセルした

    @Value("${frontend.url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode()
                : null;
        String shown = code != null && SHOWN_CODES.contains(code) ? code : "oauth_failed";
        // 코드만 남긴다 — 예외 문구에는 프로바이더가 돌려준 값이 섞일 수 있다.
        // [JA] コードだけを残す — 例外メッセージにはプロバイダが返した値が混ざり得る。
        log.info("소셜 로그인 실패. code={}", code);
        response.sendRedirect(frontendUrl + "/signin?error=" + shown);
    }
}
