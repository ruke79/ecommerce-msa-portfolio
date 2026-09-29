import { countries } from "components/checkout/countries";

/**
 * 나라 — 저장은 코드(ISO 3166-1 alpha-2), 화면은 지금 언어의 이름 (docs/60 §11).
 *
 * <p>예전에는 주소 폼이 영어 이름("Korea, Republic of")을 <b>값으로</b> 저장했다. 그래서 일본어 화면에서도 목록·주소
 * 카드·주문 상세가 영어였고, 번역하면 저장값이 언어마다 달라진다. 이제 코드를 저장하고 이름은 그릴 때 만든다 —
 * 이름표는 브라우저의 {@code Intl.DisplayNames}(CLDR)가 가지고 있어 250개를 사전에 옮겨 적지 않는다.
 *
 * <p>이미 저장된 주소는 영어 이름이다. 그래서 두 모양을 모두 읽는다({@link toCountryCode}). 서버(delivery-service
 * {@code CountryCodes})도 이름·코드를 모두 alpha-2 로 바꾸므로, 섞여 있어도 배송은 같다.
 */

const norm = (s) => String(s ?? "")
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");

const BY_CODE = new Map(countries.map((c) => [c.code, c]));
const BY_NAME = new Map(countries.map((c) => [norm(c.name), c.code]));

/** 발송국 — 주소 폼의 기본값. */
export const DEFAULT_COUNTRY = "KR";

/** 저장값(코드 또는 옛 영어 이름) → 코드. 모르면 null. */
export function toCountryCode(value) {
    if (!value) return null;
    const upper = String(value).trim().toUpperCase();
    if (BY_CODE.has(upper)) return upper;
    return BY_NAME.get(norm(value)) ?? null;
}

const displayNames = new Map();

function regionNames(lang) {
    if (!displayNames.has(lang)) {
        let names = null;
        try {
            names = typeof Intl !== "undefined" && Intl.DisplayNames
                ? new Intl.DisplayNames([lang], { type: "region", fallback: "none" })
                : null;
        } catch {
            names = null;
        }
        displayNames.set(lang, names);
    }
    return displayNames.get(lang);
}

/**
 * 저장값 → 화면 이름. 코드로 읽히면 그 언어의 이름(없으면 목록의 영어 이름), 못 읽으면 저장값 그대로 —
 * 사람이 직접 쓴 값일 수 있다.
 */
export function countryName(value, lang) {
    const code = toCountryCode(value);
    if (!code) return value ?? "";
    let name;
    try {
        name = regionNames(lang)?.of(code);
    } catch {
        name = undefined;
    }
    return name && name !== code ? name : BY_CODE.get(code).name;
}

const optionCache = new Map();

/** 주소 폼의 선택지 — {code, name}, 그 언어의 이름 순서로(일본어면 五十音順). */
export function countryOptions(lang) {
    if (!optionCache.has(lang)) {
        const collator = new Intl.Collator(lang);
        optionCache.set(lang, countries
            .map((c) => ({ code: c.code, name: countryName(c.code, lang) }))
            .sort((a, b) => collator.compare(a.name, b.name)));
    }
    return optionCache.get(lang);
}
