package com.project.gatewayserver.filter;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

/**
 * 공개 데모 계정(토큰 클레임 {@value #DEMO_CLAIM})이 쓸 수 없는 경로 — 게이트웨이 한 곳에서 403(docs/100 — 데모 계정).
 *
 * <p>데모 계정은 여러 방문자가 돌려 쓴다. 그래서 <b>다른 방문자를 내쫓거나 계정을 가로채는 일</b>(비밀번호 · 이메일 · 2단계 인증 ·
 * 패스키 · 모든 기기 로그아웃 · 탈퇴), <b>남에게 보이는 글</b>(리뷰), <b>바깥으로 나가는 비용</b>(인증 SMS)을 막는다. 둘러보기 ·
 * 장바구니 · 찜 · 주소록 · 주문 · 시험 결제 · 상담 채팅은 그대로다(운영 결제는 모두 시험 모드).
 *
 * <p>경로는 <b>원래 요청 경로</b>(서비스 이름까지 — {@code /user-service/...})로 비교한다 — 이 필터는 RewritePath 뒤에서 돌아,
 * 재작성된 경로로는 어느 서비스의 것인지 가를 수 없다. 퍼센트 인코딩은 풀고, 겹친 '/' 는 하나로 본다(우회 방지).
 * 읽기(GET · HEAD · OPTIONS)는 막지 않는다.
 *
 * <p>[JA] 公開デモアカウント(トークンのクレーム demo)が使えない経路 — ゲートウェイ一か所で 403。デモアカウントは複数の訪問者が
 *      使い回すので、他の訪問者を追い出す · アカウントを乗っ取ること(パスワード · メール · 2 段階認証 · パスキー · 全端末ログアウト ·
 *      退会)、他人に見える投稿(レビュー)、外へ出る費用(認証 SMS)を止める。経路は元の要求経路(サービス名まで)で比べる。読み取りは止めない。
 */
final class DemoAccessPolicy {

    /** 토큰의 데모 표시 — user-service JwtUtils 가 데모 계정에만 싣는다. [JA] トークンのデモ表示。 */
    static final String DEMO_CLAIM = "demo";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    /** 데모 토큰으로 쓰기(GET · HEAD · OPTIONS 밖)를 막는 경로. [JA] デモトークンで書き込みを止める経路。 */
    static final List<String> BLOCKED_WRITES = List.of(
            // 계정 · 자격 — 바꾸면 다른 방문자가 못 들어온다 [JA] アカウント · 資格
            "/user-service/api/user/profile/update-password",
            "/user-service/api/user/profile/me",
            "/user-service/api/user/profile/me/**",
            // 세션 · 기기 — '모든 기기에서 로그아웃' 은 함께 쓰는 방문자를 모두 끊는다 [JA] セッション · 端末
            "/user-service/api/user/profile/sessions/**",
            "/user-service/api/user/profile/devices/**",
            // 2단계 인증 · 패스키 — 켜면 다른 방문자가 못 들어온다 [JA] 2 段階認証 · パスキー
            "/user-service/api/auth/enable-2fa",
            "/user-service/api/auth/disable-2fa",
            "/user-service/api/auth/verify-2fa",
            "/user-service/api/auth/2fa/**",
            "/user-service/api/auth/sms-2fa/**",
            "/user-service/api/user/passkeys/**",
            // 탈퇴 [JA] 退会
            "/user-service/api/user/withdrawal/**",
            // 남에게 보이는 글 [JA] 他人に見える投稿
            "/user-service/api/review/**",
            // 인증 SMS — 아무 번호로나 보내게 하지 않는다(비용 · 스팸) [JA] 認証 SMS — 任意の番号へ送らせない
            "/notification-service/api/notifications/phone/**");

    private DemoAccessPolicy() {
    }

    /** 데모 토큰으로 이 요청을 막아야 하나. [JA] デモトークンでこの要求を止めるべきか。 */
    static boolean blocked(HttpMethod method, URI originalUri) {
        if (method == null || READS.contains(method) || originalUri == null) {
            return false;
        }
        String path = normalize(originalUri.getPath());
        return BLOCKED_WRITES.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    /** 겹친 '/' 를 하나로, 끝의 '/' 를 떼고, 대소문자는 그대로(경로는 대소문자를 가린다). [JA] 重なった '/' を一つに。 */
    static String normalize(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty()) {
            return "/";
        }
        String p = decodedPath.replaceAll("/{2,}", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
