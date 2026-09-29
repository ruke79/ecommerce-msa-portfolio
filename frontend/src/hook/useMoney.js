import { useCallback } from "react";
import { useCurrentLang } from "i18n/I18nProvider";
import { LOCALES, STORE_CURRENCY, formatMoney } from "util/money";

/**
 * 금액을 앱 언어에 맞춰 보여 주는 함수를 돌려준다 — {@code money(59.98)} → "US$59.98"(ko) · "$59.98"(ja).
 *
 * <p>통화를 주지 않으면 <b>상점 통화</b>다(카탈로그 가격·장바구니·주문 총액). 청구 통화(포트원 KRW 등)는 두 번째
 * 인자로 준다. 🐛 예전에는 화면마다 통화를 손으로 붙였다 — {@code 59.98$} · {@code USD59.98 $} · {@code $59.98}.
 *
 * <p>i18n Provider 가 없으면(일부 단위 테스트) 기본 로캘로 그린다 — 형식은 문구와 달리 없어도 된다.
 */
export function useMoney() {
    const lang = useCurrentLang();
    const locale = lang ? LOCALES[lang] : undefined;
    return useCallback(
        (amount, currency = STORE_CURRENCY) => formatMoney(Number(amount) || 0, currency, locale),
        [locale]
    );
}

export default useMoney;
