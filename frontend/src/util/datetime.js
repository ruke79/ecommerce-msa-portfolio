/**
 * 서버가 보낸 시각을 읽고 사람이 읽는 글자로 바꾼다.
 *
 * ## 🐛 화면의 시각이 9시간 어긋나 있었다
 *
 * 서버는 시각을 `LocalDateTime` 으로 저장하고 **시간대 표시 없이** 내려보낸다:
 *
 * ```
 * "2026-09-20T00:27:39.97"
 * ```
 *
 * 값 자체는 UTC 다(서비스들이 `LocalDateTime.now(ZoneOffset.UTC)` 로 만들고, 컨테이너의
 * 기본 시간대도 UTC 다). 그런데 자바스크립트는 **시간대가 없는 날짜-시각 문자열을 현지
 * 시각으로 읽는다** — 명세가 그렇다. 그래서 서울(UTC+9)에서 보면 모든 시각이 9시간
 * 이르게 찍혔다. 주문 시각, 배송 생성 시각, 무통장입금 기한까지.
 *
 * 자릿수도 형식도 멀쩡해 보여서 "시간이 좀 이상한데" 로만 느껴진다.
 *
 * ## 왜 한곳에 모으나
 *
 * 규칙 자체는 어렵지 않다 — 시간대 표시가 없으면 `Z` 를 붙이면 된다. 문제는 그 한 줄이
 * 화면마다 복제된다는 것이고, 실제로 스무 곳 중 다섯 곳만 붙이고 있었다. 나머지 열다섯
 * 곳이 전부 9시간씩 틀렸다. `datetime.test.js` 가 새로 생기는 복제를 막는다.
 *
 * ## 날짜만 있는 값은 다르게 읽는다
 *
 * `"2026-09-20"` 처럼 시각이 없는 값에 `Z` 를 붙이면 자정 UTC 가 되고, 시간대에 따라
 * **달력 날짜가 하루 밀린다**(UTC-5 에서는 9월 19일이 된다). 날짜만 있는 값은 애초에
 * 시간대와 무관한 달력 날짜이므로 현지 자정으로 읽는다.
 */

import { getCurrentLang } from "i18n/I18nProvider";
import { LOCALES } from "util/money";

/**
 * 표시 형식의 로케일 — 앱 언어를 따른다(docs/60).
 *
 * <p>일본어 화면이면 {@code ja-JP} 로 그린다. 브라우저가 한국어면 기본 형식이 "2026. 9. 20. 오후 3:00:00" 이라
 * 일본어 화면에 한국어("오후")가 남는다. 한국어 화면은 예전처럼 브라우저 기본 형식(undefined)을 쓴다 — 기준 언어라
 * 여태 보이던 모양을 바꾸지 않는다.
 */
const displayLocale = () => (getCurrentLang() === "ja" ? LOCALES.ja : undefined);

/** 시간대 표시(Z 또는 ±hh:mm)가 붙어 있나. */
const HAS_ZONE = /([zZ]|[+-]\d{2}:?\d{2})$/;

/** 날짜만 있는 값인가. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 서버가 보낸 값을 Date 로. 읽을 수 없으면 null 이다 — 예외를 던지지 않는다.
 * 화면 한 칸 때문에 그 페이지 전체가 빈 화면이 되면 안 된다.
 */
export function parseServerDate(value) {
    if (value === null || value === undefined || value === "") return null;
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
    if (typeof value === "number") {
        const fromEpoch = new Date(value);
        return Number.isNaN(fromEpoch.getTime()) ? null : fromEpoch;
    }

    const raw = String(value).trim();
    if (!raw) return null;

    let text = raw;
    if (DATE_ONLY.test(raw)) {
        // 달력 날짜다. 현지 자정으로 읽어야 어느 시간대에서도 같은 날짜로 보인다.
        text = `${raw}T00:00:00`;
    } else if (!HAS_ZONE.test(raw)) {
        // 서버의 naive 시각은 UTC 다.
        text = `${raw}Z`;
    }

    const parsed = new Date(text);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** 날짜 + 시각. 목록·이력처럼 "언제" 가 중요한 곳. */
export function formatDateTime(value, fallback = "-") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleString(displayLocale()) : fallback;
}

/** 날짜만. 게시일처럼 시각이 의미 없는 곳. */
export function formatDate(value, fallback = "-") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleDateString(displayLocale()) : fallback;
}

/** 시각만. 같은 날 안에서의 순서를 보여주는 곳(채팅 등). */
export function formatTime(value, fallback = "") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleTimeString(displayLocale() ?? [], { hour: "2-digit", minute: "2-digit" }) : fallback;
}

/** 정렬용 밀리초. 읽을 수 없으면 0 — 목록 맨 뒤로 간다. */
export function timeOf(value) {
    const parsed = parseServerDate(value);
    return parsed ? parsed.getTime() : 0;
}

/**
 * 관리자 입력칸 {@code <input type="datetime-local">} ↔ 서버 시각.
 *
 * ## 🐛 기획전 시작일을 "오늘" 로 넣었는데 "내일" 로 등록됐다
 *
 * 입력칸의 값({@code "2026-09-27T16:00"})은 **사람이 보는 현지 시각**이다. 그걸 그대로 보내면 서버는 시간대 없는
 * 시각을 UTC 로 저장한다(위 설명과 같은 규칙). 서울에서 16:00 을 넣으면 "UTC 16:00" = **서울 다음 날 01:00** 이 된다.
 * 목록은 서버 값을 UTC 로 읽어 현지로 바꿔 보여 주니 "내일" 로 보였고, 서버의 시작 판정(UTC)도 실제로 9시간 늦었다.
 * 읽을 때만 바꾸고 쓸 때는 바꾸지 않아서 생긴 비대칭이다. 기획전 · 배너 · 홈 섹션 · 공지 알림 네 화면이 같았다.
 */

const pad2 = (n) => String(n).padStart(2, "0");

/** 입력칸 값(현지 시각) → 서버로 보낼 UTC 시각(시간대 표시 없음). 비었거나 읽을 수 없으면 null. */
export function localInputToServer(value) {
    if (!value) return null;
    // 시간대 없는 "YYYY-MM-DDTHH:mm" 는 명세상 현지 시각으로 읽힌다 — 여기서는 그게 맞다(사람이 친 값이다).
    const local = new Date(value);
    if (Number.isNaN(local.getTime())) return null;
    return local.toISOString().slice(0, 19);
}

/** 서버 시각(UTC) → 입력칸 값(현지 시각, "YYYY-MM-DDTHH:mm"). 비었거나 읽을 수 없으면 "". */
export function serverToLocalInput(value) {
    const d = parseServerDate(value);
    if (!d) return "";
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}
