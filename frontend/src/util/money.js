/**
 * 청구 통화·금액.
 *
 * <p>주문에는 두 금액이 있다. {@code total} 은 <b>상점 통화</b>(카탈로그·매출의 통화)이고,
 * {@code chargedAmount}/{@code currency} 는 <b>제공자에게 실제로 청구하는</b> 금액이다 —
 * 포트원은 KRW, 일본 테스트는 JPY. 결제 화면이 제공자에게 넘기는 것은 <b>후자</b>다.
 *
 * <p>옛 주문에는 청구 통화가 없다. 그때는 상점 통화로 total 이다 — 그 컬럼이 생기기 전의
 * 의미 그대로다.
 */

/** 상점 통화. 서버의 STORE_CURRENCY 와 같아야 한다. 빌드 시점에 박힌다(B-51). */
export const STORE_CURRENCY = process.env.REACT_APP_STORE_CURRENCY || "USD";

/** 이 주문에서 제공자에게 청구할 통화·금액. */
export function chargeOf(order) {
    if (order?.currency && order?.chargedAmount != null) {
        return { currency: String(order.currency).toUpperCase(), amount: Number(order.chargedAmount) };
    }
    return { currency: STORE_CURRENCY, amount: Number(order?.total ?? 0) };
}

/** 통화의 소수 자릿수 — KRW·JPY 0, 그 밖에 2. Intl 이 알지만 여기서도 필요하다(PayPal 문자열). */
export function fractionDigits(currency) {
    try {
        const parts = new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions();
        return parts.maximumFractionDigits ?? 2;
    } catch {
        return 2;
    }
}

/**
 * PayPal 에 넘기는 금액 문자열 — USD "59.98", JPY "1500".
 *
 * <p>JPY 에 "1500.00" 을 보내면 PayPal 이 DECIMALS_NOT_SUPPORTED 로 거절한다.
 */
export function amountString(amount, currency) {
    return Number(amount).toFixed(fractionDigits(currency));
}

/** 사람에게 보여 주는 형식 — ¥1,500 · ₩80,000 · $59.98. */
export function formatMoney(amount, currency, locale) {
    try {
        return new Intl.NumberFormat(locale, { style: "currency", currency }).format(Number(amount));
    } catch {
        return `${amount} ${currency}`;
    }
}

/** 앱 언어 → 숫자·통화 표기 로캘. 통화 기호의 모양(US$ · $)과 자릿점은 언어를 따른다. */
export const LOCALES = { ko: "ko-KR", ja: "ja-JP" };

/**
 * 상점 통화로 보여 준다 — 카탈로그 가격·장바구니·주문 총액은 모두 상점 통화다.
 *
 * <p>🐛 예전에는 화면마다 통화를 손으로 붙였다 — {@code 59.98$}, {@code USD59.98 $}, {@code 59.98 $}.
 * 기호 위치도 표기도 제각각이었고, 상점 통화가 바뀌면(STORE_CURRENCY) 화면은 그대로 "$" 였다.
 */
export function formatStoreMoney(amount, locale) {
    return formatMoney(Number(amount) || 0, STORE_CURRENCY, locale);
}

/** 포트원 V2 의 통화 코드 — CURRENCY_KRW · CURRENCY_JPY · CURRENCY_USD. */
export function portOneCurrencyCode(currency) {
    return `CURRENCY_${String(currency).toUpperCase()}`;
}
