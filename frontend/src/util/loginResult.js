/**
 * 로그인 <b>성공</b> 응답을 화면 문구로 바꾼다.
 *
 * <p>🐛 예전에는 {@code toast.success(response.data.message)} 였다. 그 message 는 서버가
 * 만든 <b>영어 문장</b>이라("Login Success",
 * "User attempted to log in multiply, logging out from previous logged in state"),
 * 화면 나머지가 한국어·일본어인데 로그인 직후에만 영어가 떴다.
 *
 * <p>서버에서 번역하지 않는 이유: <b>서버는 사용자의 표시 언어를 모른다.</b> 언어는
 * 브라우저가 고르고 localStorage 에 있다(docs/45). {@code Accept-Language} 로 추측할 수는
 * 있지만 화면에서 고른 값과 어긋난다. 그래서 서버는 <b>무슨 일이 있었는지</b>만 코드로
 * 알리고(`LoginResponse.code`), 문구는 여기서 사전이 고른다. 실패 경로가 이미 쓰는
 * 방식과 같다({@code email_not_verified}).
 *
 * <p><b>왜 코드 목록을 따로 두는가.</b> {@code t()} 는 없는 키를 받으면 <b>키 문자열을
 * 그대로 돌려준다.</b> 그러니 모르는 코드를 그냥 넘기면 토스트에
 * "auth.signin.result.xyz" 가 뜬다 — 서버가 준 영어 문장보다 나쁘다. 아는 코드만 번역하고
 * 나머지는 서버 문구로 떨어뜨린다.
 */

/** 사전에 문구가 있는 코드. 서버에 새 코드가 생기면 여기와 ko/ja 에 함께 넣는다. */
export const KNOWN_RESULT_CODES = ["login_success", "multi_login"];

/**
 * @param {object} data 로그인 응답 본문 ({ email, roles, message, code })
 * @param {(key: string) => string} t 사전 조회 함수
 */
export function loginResultMessage(data, t) {
    const code = data?.code;
    if (typeof code === "string" && KNOWN_RESULT_CODES.includes(code)) {
        return t(`auth.signin.result.${code}`);
    }

    // 코드를 모른다 = 서버가 우리보다 새 것이다. 그쪽 문구가 그나마 정확하다.
    const fromServer = data?.message;
    if (typeof fromServer === "string" && fromServer.trim()) {
        return fromServer;
    }

    // 둘 다 없어도 토스트는 떠야 한다 — 로그인은 성공했으니까.
    return t("auth.signin.result.login_success");
}
