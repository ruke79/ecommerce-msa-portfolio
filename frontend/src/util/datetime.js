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
 *
 * <p>[JA] サーバーが送った時刻を読み、人が読む文字に変える。
 *
 * ## 🐛 画面の時刻が 9 時間ずれていた
 *
 * <p>サーバーは時刻を `LocalDateTime` で保存し、**タイムゾーン表示なしで** 返す:
 *
 * ```
 * "2026-09-20T00:27:39.97"
 * ```
 *
 * <p>値自体は UTC である(サービスが `LocalDateTime.now(ZoneOffset.UTC)` で作り、コンテナの
 * 既定のタイムゾーンも UTC)。ところが JavaScript は **タイムゾーンのない日付時刻文字列を現地
 * 時刻として読む** — 仕様がそうなっている。そのためソウル(UTC+9)で見るとすべての時刻が 9 時間
 * 早く表示された。注文時刻、配送作成時刻、銀行振込の期限まで。
 *
 * <p>桁数も形式も正常に見えるので「時間がちょっと変だな」としか感じられない。
 *
 * ## なぜ一か所に集めるか
 *
 * <p>規則自体は難しくない — タイムゾーン表示がなければ `Z` を付ければよい。問題はその一行が
 * 画面ごとに複製されることで、実際に 20 か所のうち 5 か所しか付けていなかった。残りの 15
 * か所がすべて 9 時間ずつ間違っていた。`datetime.test.js` が新たな複製を防ぐ。
 *
 * ## 日付だけの値は別に読む
 *
 * `"2026-09-20"` のように時刻のない値に `Z` を付けると UTC の深夜 0 時になり、タイムゾーンによって
 * **カレンダーの日付が一日ずれる**(UTC-5 では 9 月 19 日になる)。日付だけの値はそもそも
 * タイムゾーンと無関係なカレンダーの日付なので、現地の深夜 0 時として読む。
 */

import { getCurrentLang } from "i18n/I18nProvider";
import { LOCALES } from "util/money";

/**
 * 표시 형식의 로케일 — 앱 언어를 따른다(docs/60).
 *
 * <p>일본어 화면이면 {@code ja-JP} 로 그린다. 브라우저가 한국어면 기본 형식이 "2026. 9. 20. 오후 3:00:00" 이라
 * 일본어 화면에 한국어("오후")가 남는다. 한국어 화면은 예전처럼 브라우저 기본 형식(undefined)을 쓴다 — 기준 언어라
 * 여태 보이던 모양을 바꾸지 않는다.
 *
 * <p>[JA] 表示形式のロケール — アプリの言語に従う(docs/60)。
 *
 * <p>日本語画面なら {@code ja-JP} で描く。ブラウザが韓国語だと既定の形式が "2026. 9. 20. 오후 3:00:00" なので
 * 日本語画面に韓国語(「오후」)が残る。韓国語画面は従来どおりブラウザの既定形式(undefined)を使う — 基準言語なので
 * これまで見えていた形を変えない。
 */
const displayLocale = () => (getCurrentLang() === "ja" ? LOCALES.ja : undefined);

/** 시간대 표시(Z 또는 ±hh:mm)가 붙어 있나. */
// [JA] タイムゾーン表示(Z または ±hh:mm)が付いているか。
const HAS_ZONE = /([zZ]|[+-]\d{2}:?\d{2})$/;

/** 날짜만 있는 값인가. */
// [JA] 日付だけの値か。
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 서버가 보낸 값을 Date 로. 읽을 수 없으면 null 이다 — 예외를 던지지 않는다.
 * 화면 한 칸 때문에 그 페이지 전체가 빈 화면이 되면 안 된다.
 *
 * <p>[JA] サーバーが送った値を Date に。読めなければ null — 例外は投げない。
 * 画面の一欄のためにそのページ全体が空白になってはならない。
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
        // [JA] カレンダーの日付である。現地の深夜 0 時として読んでこそ、どのタイムゾーンでも同じ日付に見える。
        text = `${raw}T00:00:00`;
    } else if (!HAS_ZONE.test(raw)) {
        // 서버의 naive 시각은 UTC 다.
        // [JA] サーバーの naive な時刻は UTC である。
        text = `${raw}Z`;
    }

    const parsed = new Date(text);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** 날짜 + 시각. 목록·이력처럼 "언제" 가 중요한 곳. */
// [JA] 日付 + 時刻。一覧・履歴のように「いつ」が重要な所。
export function formatDateTime(value, fallback = "-") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleString(displayLocale()) : fallback;
}

/** 날짜만. 게시일처럼 시각이 의미 없는 곳. */
// [JA] 日付だけ。掲載日のように時刻に意味がない所。
export function formatDate(value, fallback = "-") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleDateString(displayLocale()) : fallback;
}

/** 시각만. 같은 날 안에서의 순서를 보여주는 곳(채팅 등). */
// [JA] 時刻だけ。同じ日の中での順序を見せる所(チャットなど)。
export function formatTime(value, fallback = "") {
    const parsed = parseServerDate(value);
    return parsed ? parsed.toLocaleTimeString(displayLocale() ?? [], { hour: "2-digit", minute: "2-digit" }) : fallback;
}

/** 정렬용 밀리초. 읽을 수 없으면 0 — 목록 맨 뒤로 간다. */
// [JA] ソート用のミリ秒。読めなければ 0 — 一覧の最後尾へ行く。
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
 *
 * <p>[JA] 管理者の入力欄 {@code <input type="datetime-local">} ↔ サーバー時刻。
 *
 * ## 🐛 特集の開始日を「今日」で入れたのに「明日」で登録された
 *
 * <p>入力欄の値({@code "2026-09-27T16:00"})は **人が見る現地時刻** である。それをそのまま送るとサーバーはタイムゾーンのない
 * 時刻を UTC として保存する(上の説明と同じ規則)。ソウルで 16:00 を入れると「UTC 16:00」= **ソウルの翌日 01:00** になる。
 * 一覧はサーバーの値を UTC として読み現地に変えて見せるので「明日」に見え、サーバーの開始判定(UTC)も実際に 9 時間遅れた。
 * 読むときだけ変換して書くときは変換しなかったことで生じた非対称である。特集・バナー・ホームセクション・お知らせ通知の四画面が同じだった。
 */

const pad2 = (n) => String(n).padStart(2, "0");

/** 입력칸 값(현지 시각) → 서버로 보낼 UTC 시각(시간대 표시 없음). 비었거나 읽을 수 없으면 null. */
// [JA] 入力欄の値(現地時刻) → サーバーへ送る UTC 時刻(タイムゾーン表示なし)。空か読めなければ null。
export function localInputToServer(value) {
    if (!value) return null;
    // 시간대 없는 "YYYY-MM-DDTHH:mm" 는 명세상 현지 시각으로 읽힌다 — 여기서는 그게 맞다(사람이 친 값이다).
    // [JA] タイムゾーンのない "YYYY-MM-DDTHH:mm" は仕様上現地時刻として読まれる — ここではそれが正しい(人が打った値である)。
    const local = new Date(value);
    if (Number.isNaN(local.getTime())) return null;
    return local.toISOString().slice(0, 19);
}

/** 서버 시각(UTC) → 입력칸 값(현지 시각, "YYYY-MM-DDTHH:mm"). 비었거나 읽을 수 없으면 "". */
// [JA] サーバー時刻(UTC) → 入力欄の値(現地時刻、"YYYY-MM-DDTHH:mm")。空か読めなければ ""。
export function serverToLocalInput(value) {
    const d = parseServerDate(value);
    if (!d) return "";
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}
