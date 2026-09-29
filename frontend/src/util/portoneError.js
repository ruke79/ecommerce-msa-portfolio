/**
 * 포트원 결제창의 실패 → 화면 언어의 안내 (docs/60 §12).
 *
 * <p>결제창이 실패하면 포트원은 <b>PG 가 쓴 문구</b>를 그대로 넘긴다 — V1 은 {@code error_msg}
 * ("[결제포기] 사용자가 결제를 취소하셨습니다"), V2 는 {@code message} 와 {@code code}. 국내 PG 라 대개 한국어이고,
 * 우리가 고를 수 없다. 예전에는 그 글자를 그대로 띄워서 일본어 화면에도 한국어가 나왔다.
 *
 * <p>그래서 <b>무슨 일인지를 알아보고</b> 사전 문구로 다시 말한다 — 취소, 잔액·한도, 카드 정보, 인증, 시간 초과. 알아보지
 * 못한 문구는:
 * <ul>
 *   <li>한국어 화면이면 PG 의 문구를 그대로 보여 준다 — 원인을 가장 잘 말해 주는 것이 그 문구다.</li>
 *   <li>다른 언어면 일반 안내에 <b>오류 코드만</b> 붙인다(있으면) — 고객센터가 찾을 수 있는 단서는 남기고, 읽을 수 없는
 *       한국어는 보여 주지 않는다. 원문은 콘솔에 남긴다.</li>
 * </ul>
 *
 * <p>순서가 중요하다 — "인증 시간이 초과" 는 시간 초과로, "카드 비밀번호 오류" 는 카드 정보로 읽힌다. 취소가 가장 먼저다:
 * PG 는 "결제를 취소" 를 여러 모양으로 쓰고, 그것이 가장 흔한 실패다.
 */

/** [유형, 사전 키, 알아보는 모양]. 첫 번째로 맞는 것이 이긴다. */
const RULES = [
    ["canceled", "payment.portone.error.canceled",
        [/결제\s*포기/, /취소\s*하(셨|였|였습)/, /사용자.*취소/, /cancel/i, /PAY_PROCESS_(CANCELED|ABORTED)/]],
    ["timeout", "payment.portone.error.timeout",
        [/시간\s*(이\s*)?초과/, /timeout/i, /timed?\s*out/i, /expired/i]],
    ["insufficient", "payment.portone.error.insufficient",
        [/잔액\s*(이\s*)?부족/, /한도\s*(가\s*)?초과/, /한도\s*부족/, /insufficient/i, /limit/i]],
    ["card", "payment.portone.error.card",
        [/카드\s*번호/, /유효\s*기간/, /비밀\s*번호/, /CVC|CVV/i, /할부/, /카드.*(오류|불가|정지|분실|도난)/, /invalid\s*card/i]],
    ["auth", "payment.portone.error.auth",
        [/본인\s*인증/, /인증.*(실패|오류)/, /3-?D\s*Secure/i, /authenticat/i]],
];

/** 포트원 응답(V1·V2)에서 PG 문구와 코드를 꺼낸다. */
export function portOneFailure(response) {
    const message = response?.error_msg ?? response?.message ?? response?.pgMessage ?? "";
    const code = response?.error_code ?? response?.code ?? response?.pgCode ?? "";
    return { message: String(message), code: String(code) };
}

/** 어떤 실패인지 — 알아보지 못하면 null. */
export function classifyPortOneFailure(response) {
    const { message, code } = portOneFailure(response);
    const text = `${message} ${code}`;
    const rule = RULES.find(([, , patterns]) => patterns.some((p) => p.test(text)));
    return rule ? { kind: rule[0], key: rule[1] } : null;
}

/**
 * 화면에 띄울 문구.
 *
 * @param t    사전
 * @param lang 화면 언어
 * @param response 포트원 결제창의 응답(V1 콜백 인자 · V2 requestPayment 결과)
 */
export function portOneFailureText(t, lang, response) {
    const known = classifyPortOneFailure(response);
    if (known) return t(known.key);

    const { message, code } = portOneFailure(response);
    if (message && lang === "ko") return message;
    if (message) {
        // eslint-disable-next-line no-console
        console.warn("PortOne failure (untranslated):", code, message);
    }
    return code
        ? t("payment.portone.error.generic_code", { code })
        : t("payment.error.not_completed");
}
