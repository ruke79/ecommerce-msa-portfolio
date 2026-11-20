/**
 * 로그인 실패를 <b>사용자가 무엇을 해야 하는지</b>로 바꾼다.
 *
 * <p>🐛 예전에는 어떤 이유로 실패하든 화면에 "Login Failed." 한 줄만 떴다. 원인이 셋이었다:
 *
 * <ol>
 *   <li>서버의 {@code attemptAuthentication} 이 인증 예외까지 삼켜 <b>본문 없는 400</b> 을
 *       내보냈다 — 실패 사유를 만드는 코드는 있었지만 불리지 않는 죽은 코드였다.</li>
 *   <li>그걸 고쳐 401 + 사유가 와도, axios 인터셉터가 그 401 을 "세션 만료"로 보고 토큰
 *       갱신을 시도한 뒤 오류를 {@code Error("token refresh failed")} 로 갈아치웠다.</li>
 *   <li>화면의 기본 문구가 영어 한 줄이라, 서버가 아무것도 못 줬을 때 사용자가 할 수 있는
 *       일이 없었다.</li>
 * </ol>
 *
 * <p>셋 다 고친 뒤 <b>남는 경우</b>를 이 함수가 맡는다 — 서버에 닿지도 못한 경우다.
 * 그때는 서버가 보낸 문구가 없으므로 상황을 보고 우리가 만든다.
 *
 * <p><b>서버 문구를 항상 우선한다.</b> 잠금까지 남은 시간이나 메일 인증 안내처럼, 서버만
 * 아는 사실이 담겨 있다. 여기서 다시 분류하면 같은 규칙이 두 곳에 생기고 언젠가 어긋난다.
 *
 * <p>[JA] ログイン失敗を<b>ユーザーが何をすべきか</b>に変える。
 *
 * <p>🐛 以前はどんな理由で失敗しても画面に "Login Failed." の一行だけが出た。原因は三つあった:
 *
 * <ol>
 *   <li>サーバーの {@code attemptAuthentication} が認証例外まで握りつぶし、<b>ボディのない 400</b> を
 *       返していた — 失敗理由を作るコードはあったが、呼ばれない死んだコードだった。</li>
 *   <li>それを直して 401 + 理由が来ても、axios インターセプターがその 401 を「セッション切れ」とみなしてトークン
 *       更新を試み、エラーを {@code Error("token refresh failed")} にすり替えた。</li>
 *   <li>画面の既定の文言が英語一行だったので、サーバーが何も返せなかったときにユーザーができる
 *       ことがなかった。</li>
 * </ol>
 *
 * <p>三つとも直したあとに<b>残る場合</b>をこの関数が受け持つ — サーバーに届きすらしなかった場合である。
 * そのときはサーバーが送った文言がないので、状況を見て我々が作る。
 *
 * <b>サーバーの文言を常に優先する。</b> ロックまでの残り時間やメール認証の案内のように、サーバーだけが
 * 知る事実が入っている。ここで再分類すると同じ規則が二か所にでき、いつかずれる。
 */

import { translate } from "i18n/I18nProvider";

/**
 * 서버가 아무 말도 못 해 줬을 때의 기본 문구.
 *
 * <p>상수가 아니라 함수다 — 모듈을 읽는 순간의 언어로 굳지 않고, 부르는 그때의 언어로 만든다.
 *
 * <p>[JA] サーバーが何も返せなかったときの既定の文言。
 *
 * <p>定数ではなく関数である — モジュールを読み込んだ瞬間の言語で固まらず、呼ぶそのときの言語で作る。
 */
export const fallbackMessage = () => translate("login_error.fallback");

export function loginErrorMessage(error) {
    const fromServer = error?.response?.data?.message;
    if (typeof fromServer === "string" && fromServer.trim()) {
        return fromServer;
    }

    // 응답 자체가 없다 = 요청이 서버에 닿지 못했다. "비밀번호가 틀렸다"로 보이면
    // 사용자는 맞는 비밀번호를 의심하며 계속 다시 친다.
    // [JA] 応答そのものがない = リクエストがサーバーに届かなかった。「パスワードが違う」と見えると
    //      ユーザーは正しいパスワードを疑って何度も打ち直す。
    if (!error?.response) {
        if (error?.code === "ECONNABORTED" || /timeout/i.test(error?.message ?? "")) {
            return translate("login_error.timeout");
        }
        return translate("login_error.network");
    }

    switch (error.response.status) {
        case 400:
            return translate("login_error.missing_fields");
        case 401:
        case 403:
            // 서버가 본문을 못 준 401 — 형식이 바뀌었거나 프록시가 본문을 잘랐다.
            // [JA] サーバーがボディを返せなかった 401 — 形式が変わったか、プロキシがボディを切った。
            return translate("login_error.bad_credentials");
        case 429:
            return translate("login_error.too_many");
        case 503:
            return translate("auth2.service_unavailable");
        default:
            return fallbackMessage();
    }
}

/**
 * 인터셉터가 이미 토스트를 띄우는 상태인가.
 *
 * <p>503 은 {@code TokenRefresher} 가 전역 토스트를 띄운다. 화면에서 또 띄우면 같은 말이
 * 두 번 쌓인다 — 인라인 메시지는 남기되 토스트는 한 번만.
 *
 * <p>[JA] インターセプターがすでにトーストを出している状態か。
 *
 * <p>503 は {@code TokenRefresher} がグローバルなトーストを出す。画面でもまた出すと同じことが
 * 二度重なる — インラインのメッセージは残すが、トーストは一度だけ。
 */
export function alreadyToasted(error) {
    return error?.response?.status === 503;
}
