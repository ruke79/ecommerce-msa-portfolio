import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import ko from "./messages/ko";
import ja from "./messages/ja";

/**
 * 화면 문구의 다국어 처리 — <b>의존성 없이</b>.
 *
 * <p><b>왜 react-i18next 를 쓰지 않았나.</b> 이 저장소는 새 의존성을 늘리지 않는다는 방침이
 * 있고({@code jest.config.js} 주석: "네트워크가 막힌 환경에서도 바로 돌아야 한다"),
 * 그 방침을 깨뜨릴 만한 이유가 이 경우에는 없다:
 *
 * <ul>
 *   <li>i18next 의 가장 큰 값어치는 <b>복수형 규칙</b>인데, 한국어와 일본어는 <b>둘 다
 *       복수형이 없다.</b> "1件" 도 "5件" 도 형태가 같다. 규칙 엔진이 할 일이 없다.</li>
 *   <li>남는 것은 사전 조회와 변수 치환뿐이고, 그건 아래 스무 줄이다.</li>
 *   <li>언어가 둘이라 지연 로딩도 이득이 없다 — 사전 두 개를 합쳐도 번들에서 몇 KB 다.</li>
 * </ul>
 *
 * <p>언어가 다섯 개로 늘거나 복수형이 있는 언어(영어·러시아어 등)가 들어오면 그때는
 * 라이브러리가 맞다. 그 경계를 여기 적어 둔다.
 *
 * <p><b>언어를 고르는 순서</b>: 저장된 선택 → 브라우저 언어 → 한국어. 저장된 선택이
 * 브라우저보다 앞서는 것이 중요하다 — 일본어 브라우저를 쓰는 사람이 한국어를 고를 수
 * 있어야 하고, 그 선택이 새로고침에 살아남아야 한다.
 *
 * <p>[JA] 画面文言の多言語処理 — <b>依存なしで</b>。
 * <b>なぜ react-i18next を使わなかったか。</b> このリポジトリには新しい依存を増やさないという方針が
 * あり({@code jest.config.js} のコメント: 「ネットワークが塞がれた環境でもすぐ動かなければならない」)、
 * その方針を破るだけの理由がこの場合にはない:
 * i18next の最大の価値は <b>複数形規則</b>だが、韓国語と日本語は <b>どちらも複数形がない。</b>
 * 「1件」も「5件」も形が同じだ。規則エンジンのやることがない。
 * 残るのは辞書引きと変数置換だけで、それは下の二十行だ。
 * 言語が二つなので遅延ロードも得がない — 辞書二つを合わせてもバンドルで数 KB だ。
 * 言語が五つに増えたり複数形のある言語(英語・ロシア語など)が入ったらそのときは
 * ライブラリが正しい。その境界をここに書いておく。
 * <b>言語を選ぶ順序</b>: 保存された選択 → ブラウザ言語 → 韓国語。保存された選択が
 * ブラウザより優先されることが重要だ — 日本語ブラウザを使う人が韓国語を選べ
 * なければならず、その選択がリロードを生き延びなければならない。
 */

export const SUPPORTED = ["ko", "ja"];
export const DEFAULT_LANG = "ko";

const MESSAGES = { ko, ja };

/** localStorage 키. 다른 저장값과 섞이지 않게 접두사를 붙인다. */
// [JA] localStorage のキー。他の保存値と混ざらないよう接頭辞を付ける。
const STORAGE_KEY = "app.lang";

const I18nContext = createContext(null);

/**
 * 브라우저가 알려주는 언어를 지원 목록으로 좁힌다.
 *
 * <p>{@code navigator.language} 는 {@code "ja"} 일 수도 {@code "ja-JP"} 일 수도 있고,
 * {@code languages} 배열에 선호 순서가 담긴다. 앞에서부터 보고 처음 맞는 것을 쓴다 —
 * 지역 코드는 떼고 앞 두 글자만 본다({@code ja-JP} → {@code ja}).
 *
 * <p>[JA] ブラウザが知らせる言語をサポート一覧に絞る。
 * {@code navigator.language} は {@code "ja"} のことも {@code "ja-JP"} のこともあり、
 * {@code languages} 配列に優先順が入る。前から見て最初に合うものを使う —
 * 地域コードは外し先頭二文字だけ見る({@code ja-JP} → {@code ja})。
 */
export function detectBrowserLanguage(nav = typeof navigator === "undefined" ? null : navigator) {
    if (!nav) return null;

    const candidates = [...(nav.languages ?? []), nav.language].filter(Boolean);
    for (const tag of candidates) {
        const base = String(tag).toLowerCase().split("-")[0];
        if (SUPPORTED.includes(base)) {
            return base;
        }
    }
    return null;
}

/**
 * 저장된 선택을 읽는다.
 *
 * <p>⚠️ try/catch 가 필요하다. 시크릿 창이나 사이트 데이터가 차단된 브라우저에서는
 * localStorage 접근 자체가 <b>예외를 던진다</b> — 감싸지 않으면 앱이 첫 렌더에서 죽는다.
 *
 * <p>[JA] 保存された選択を読む。
 * ⚠️ try/catch が必要だ。シークレットウィンドウやサイトデータがブロックされたブラウザでは
 * localStorage へのアクセス自体が <b>例外を投げる</b> — 包まないとアプリが最初のレンダーで落ちる。
 */
function readStoredLanguage() {
    try {
        const saved = window.localStorage.getItem(STORAGE_KEY);
        return SUPPORTED.includes(saved) ? saved : null;
    } catch {
        return null;
    }
}

function storeLanguage(lang) {
    try {
        window.localStorage.setItem(STORAGE_KEY, lang);
    } catch {
        // 저장에 실패해도 이번 세션의 언어 전환은 그대로 동작한다. 기억만 못 할 뿐이다.
        // [JA] 保存に失敗しても今回のセッションの言語切り替えはそのまま動く。記憶できないだけだ。
    }
}

/** 초기 언어. 렌더 전에 한 번만 계산한다. */
// [JA] 初期言語。レンダー前に一度だけ計算する。
export function resolveInitialLanguage() {
    return readStoredLanguage() ?? detectBrowserLanguage() ?? DEFAULT_LANG;
}

/**
 * {@code {{name}}} 자리를 값으로 바꾼다.
 *
 * <p>치환값이 없으면 자리표시자를 <b>그대로 남긴다.</b> 빈 문자열로 지우면
 * "비밀번호는 자 이상이어야 합니다" 처럼 말이 되는 듯한 문장이 되어 버그가 숨는다.
 *
 * <p>[JA] {@code {{name}}} の場所を値に置き換える。
 * 置換値が無ければプレースホルダーを <b>そのまま残す。</b> 空文字列で消すと
 * 「パスワードは 文字以上でなければなりません」のように意味が通りそうな文になりバグが隠れる。
 */
/**
 * 지금 화면의 언어 — 컴포넌트 밖(유틸·토스트·API 래퍼)에서 쓰려고 모듈에 둔다. Provider 가 언어를 정하거나 바꿀 때
 * 맞춘다. Provider 밖(테스트의 순수 함수 등)에서는 기본 언어다.
 *
 * <p>[JA] 今の画面の言語 — コンポーネント外(ユーティリティ・トースト・API ラッパー)で使うためにモジュールに置く。Provider が言語を決めたり変えたりするとき
 * 合わせる。Provider 外(テストの純粋関数など)ではデフォルト言語だ。
 */
let currentLang = DEFAULT_LANG;

export function getCurrentLang() {
    return currentLang;
}

/** 사전에서 문구를 꺼낸다 — useTranslation 의 t 와 같은 규칙(ja 에 없으면 ko, 둘 다 없으면 키). */
// [JA] 辞書から文言を取り出す — useTranslation の t と同じ規則(ja に無ければ ko、どちらにも無ければキー)。
function lookup(lang, key, vars) {
    const dict = MESSAGES[lang] ?? MESSAGES[DEFAULT_LANG];
    const template = dict[key] ?? MESSAGES[DEFAULT_LANG][key] ?? key;
    return interpolate(template, vars);
}

/**
 * 컴포넌트 밖에서 쓰는 번역(docs/60 §3) — 토스트·유틸·API 래퍼의 문구. 컴포넌트 안에서는 언어가 바뀌면 다시 그려지도록
 * {@link useTranslation} 의 t 를 쓴다. 이것은 부르는 <b>그 순간</b>의 언어로 한 번 만든다.
 *
 * <p>[JA] コンポーネント外で使う翻訳(docs/60 §3) — トースト・ユーティリティ・API ラッパーの文言。コンポーネント内では言語が変わったら再描画されるよう
 * {@link useTranslation} の t を使う。これは呼んだ <b>その瞬間</b>の言語で一度作る。
 */
export function translate(key, vars) {
    return lookup(currentLang, key, vars);
}

function interpolate(template, vars) {
    if (!vars) return template;
    return template.replace(/\{\{(\w+)\}\}/g, (match, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match
    );
}

export function I18nProvider({ children, initialLang }) {
    const [lang, setLangState] = useState(() => initialLang ?? resolveInitialLanguage());
    // 렌더 중에 맞춘다 — 자식의 첫 렌더·첫 요청(Accept-Language)부터 이 언어여야 한다(useEffect 는 늦다).
    // [JA] レンダー中に合わせる — 子の最初のレンダー・最初のリクエスト(Accept-Language)からこの言語でなければならない(useEffect では遅い)。
    currentLang = lang;

    const setLang = useCallback((next) => {
        if (!SUPPORTED.includes(next)) return;
        currentLang = next;
        setLangState(next);
        storeLanguage(next);
    }, []);

    /**
     * 🔴 {@code <html lang>} 을 실제로 바꾼다.
     *
     * <p>장식이 아니다. 스크린리더가 읽을 발음을 이 값으로 고르고, 브라우저는 줄바꿈 규칙과
     * 기본 글꼴을 여기에 맞춘다. 한국어 페이지에 일본어를 그려 놓고 {@code lang="ko"} 로
     * 두면 일본어가 <b>한국어 글꼴로</b> 렌더돼 글자 모양이 어긋난다(한중일 통합 한자).
     *
     * <p>[JA] 🔴 {@code <html lang>} を実際に変える。
     * 飾りではない。スクリーンリーダーが読む発音をこの値で選び、ブラウザは改行規則と
     * デフォルトフォントをこれに合わせる。韓国語ページに日本語を描いて {@code lang="ko"} のまま
     * にすると日本語が <b>韓国語フォントで</b> レンダーされ字形がずれる(CJK 統合漢字)。
     */
    useEffect(() => {
        document.documentElement.lang = lang;
    }, [lang]);

    /**
     * 문구를 꺼낸다.
     *
     * <p>없는 키는 <b>키 이름을 그대로</b> 돌려준다. 빈 문자열을 돌려주면 화면에서 글자가
     * 사라져 아무도 눈치채지 못한다 — 키가 보이면 즉시 드러난다.
     *
     * <p>[JA] 文言を取り出す。
     * 無いキーは <b>キー名をそのまま</b> 返す。空文字列を返すと画面から文字が
     * 消えて誰も気づかない — キーが見えればすぐ露見する。
     */
    // ja 에 아직 없는 키는 ko 로 떨어진다 — 번역이 밀려도 화면이 비지 않는다.
    // [JA] ja にまだ無いキーは ko にフォールバックする — 翻訳が遅れても画面が空にならない。
    const t = useCallback((key, vars) => lookup(lang, key, vars), [lang]);

    const value = useMemo(() => ({ lang, setLang, t }), [lang, setLang, t]);

    return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/**
 * 화면에서 문구를 쓰는 통로.
 *
 * <p>Provider 밖에서 부르면 <b>던진다.</b> 조용히 기본값으로 동작하게 하면, Provider 를
 * 빠뜨린 화면이 한국어로 고정된 채 "일본어 전환만 안 되는" 상태로 배포된다.
 *
 * <p>[JA] 画面で文言を使う通り道。
 * Provider 外で呼ぶと <b>例外を投げる。</b> 静かにデフォルトで動かすと、Provider を
 * 入れ忘れた画面が韓国語に固定されたまま「日本語切り替えだけ効かない」状態でデプロイされる。
 */
export function useTranslation() {
    const context = useContext(I18nContext);
    if (!context) {
        throw new Error("useTranslation 은 <I18nProvider> 안에서만 쓸 수 있습니다.");
    }
    return context;
}

/**
 * 지금 언어 — Provider 가 없으면 null. 던지지 않는다.
 *
 * <p>문구({@link useTranslation})와 달리 <b>표기 형식</b>(숫자·통화)은 Provider 가 없어도 기본 형식으로 그릴 수
 * 있다. 결제·환불 화면처럼 문구를 번역하지 않는 곳도 금액은 앱 언어를 따르게 하려고 둔다(useMoney).
 *
 * <p>[JA] 今の言語 — Provider が無ければ null。例外は投げない。
 * 文言({@link useTranslation})と違い <b>表記形式</b>(数値・通貨)は Provider が無くてもデフォルト形式で描ける。
 * 決済・返金画面のように文言を翻訳しない所でも金額はアプリの言語に従わせるために置く(useMoney)。
 */
export function useCurrentLang() {
    return useContext(I18nContext)?.lang ?? null;
}

export default I18nProvider;
