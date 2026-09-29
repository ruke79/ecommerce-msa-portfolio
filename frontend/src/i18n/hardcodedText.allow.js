/**
 * 하드코딩 문구 검사(hardcodedText.test.js)가 봐주는 것 — docs/60 §5.
 *
 * <p>여기에 넣는 것은 <b>번역하지 않는 것이 맞는</b> 글자뿐이다: 상표·고유명사(PayPal, Stripe), 통화·단위 기호, 코드 값.
 * "번역이 귀찮은" 문구를 넣으면 일본어 화면에 그대로 남는다 — 그러면 이 검사가 있는 이유가 없다.
 */

/** 어디에 나와도 번역하지 않는 글자(정확히 일치, 앞뒤 공백 무시). */
export const ALLOWED_TEXT = new Set([
    // 상표·서비스 이름
    "PayPal", "Stripe", "PortOne", "Google", "Kakao", "Naver", "GitHub", "ysyproject", "YSY Shop",
    "Visa", "Mastercard", "KG이니시스",
    // 통화·단위·기호
    "USD", "KRW", "JPY", "US$", "₩", "¥", "$", "%", "×", "·", "/", "-", "—", "–", "|", ":", "(", ")",
    // 언어 선택 — 각 언어의 자기 이름은 번역하지 않는다
    "한국어", "日本語", "KO", "JA",
    // 예시·상표 조각 — 입력 예시 주소, 로고 글자(ysy + project)
    "you@example.com", "ysy", "project",
    // 개발자에게 던지는 오류 — 화면에는 접힌 "기술 정보" 에만(ErrorFallback), 번역하면 검색이 어려워진다
    "token refresh failed", "useStomp must be used within a StompProvider",
]);

/**
 * 파일 통째로 빼는 것 — 사람이 보는 화면이 아니다. 경로는 src/ 기준.
 */
export const ALLOWED_FILES = new Set([
    "i18n/I18nProvider.jsx",     // 개발자에게 던지는 오류(Provider 밖에서 useTranslation)
    "i18n/hardcodedText.allow.js",
    "util/chatNotice.js",                      // 서버가 저장한 시스템 문구를 "알아보는" 모양 — 보여 주는 글은 사전에서 온다
    "components/chat/styles/BaseStyle.js",     // styled-components CSS 안의 주석뿐 — 화면에 나오지 않는다
    "components/chat/styles/GlobalStyle.js",
]);
