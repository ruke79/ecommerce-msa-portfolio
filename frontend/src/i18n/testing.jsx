import I18nProvider from "./I18nProvider";

/**
 * 테스트에서 화면을 i18n 컨텍스트로 감싼다.
 *
 * <p>{@code useTranslation} 은 Provider 밖에서 부르면 <b>던진다</b> — 조용히 기본값으로
 * 동작하게 하면 Provider 를 빠뜨린 화면이 "일본어 전환만 안 되는" 상태로 배포되기 때문이다.
 * 그 엄격함의 대가로 테스트마다 감싸는 일이 생기므로, 그 한 줄을 여기 모아 둔다.
 *
 * <p><b>언어를 인자로 받는다.</b> 기본은 한국어이고, 일본어 화면을 검증하는 테스트는
 * {@code "ja"} 를 넘긴다 — 브라우저 언어나 저장된 값에 기대지 않으므로 실행 환경이 달라도
 * 결과가 같다.
 */
export function withI18n(ui, lang = "ko") {
    return <I18nProvider initialLang={lang}>{ui}</I18nProvider>;
}

export default withI18n;
