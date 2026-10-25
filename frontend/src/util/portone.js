/**
 * 포트원(구 아임포트) V1 브라우저 SDK 로더.
 *
 * <h3>왜 스크립트를 직접 붙이는가</h3>
 *
 * <p>포트원 V1 은 npm 패키지가 아니라 {@code <script>} 로 {@code window.IMP} 를 심는
 * 방식이다. {@code index.html} 에 박아 두지 않고 <b>필요할 때 한 번만</b> 넣는 이유:
 * 결제 화면에 오지 않는 사용자에게까지 외부 스크립트를 내려받게 할 이유가 없고,
 * 무엇보다 <b>포트원을 끈 환경에서는 아예 로드되지 않아야</b> 하기 때문이다.
 *
 * <p>같은 약속을 캐시한다. 버튼을 두 번 누르거나 컴포넌트가 다시 마운트돼도 스크립트는
 * 한 번만 들어간다 — 두 번 넣으면 {@code IMP} 가 재초기화되며 진행 중인 결제창이 끊긴다.
 */

import { translate } from "i18n/I18nProvider";

const SDK_URL = "https://cdn.iamport.kr/v1/iamport.js";

let loading = null;

export function loadPortOne() {
    if (typeof window === "undefined") {
        return Promise.reject(new Error(translate("portone.not_browser")));
    }
    if (window.IMP) {
        return Promise.resolve(window.IMP);
    }
    if (loading) {
        return loading;
    }

    loading = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = SDK_URL;
        script.async = true;
        script.onload = () => {
            if (window.IMP) {
                resolve(window.IMP);
            } else {
                // 스크립트는 받았는데 전역이 없다 — 광고 차단기나 CSP 가 흔한 원인이다.
                loading = null;
                reject(new Error(translate("portone.init_failed")));
            }
        };
        script.onerror = () => {
            // 다음 시도에서 다시 붙일 수 있도록 캐시를 비운다.
            loading = null;
            reject(new Error(translate("portone.load_failed")));
        };
        document.head.appendChild(script);
    });

    return loading;
}

/** 테스트에서 모듈 상태를 되돌린다 — 캐시가 테스트 사이에 새면 결과가 순서에 좌우된다. */
export function resetPortOneLoaderForTests() {
    loading = null;
}

/**
 * 포트원 <b>V2</b> 브라우저 SDK 로더 — {@code window.PortOne}.
 *
 * <p>V1 과 전역이 다르다({@code IMP} 가 아니라 {@code PortOne}). 계정이 V2 면
 * (콘솔에 {@code imp…} 식별코드가 없고 {@code store-…} 가 보인다) 이쪽을 쓴다.
 * 로딩 규칙은 V1 과 같다 — 필요할 때 한 번만, 실패하면 다음 시도에서 다시.
 */
const SDK_V2_URL = "https://cdn.portone.io/v2/browser-sdk.js";

let loadingV2 = null;

export function loadPortOneV2() {
    if (typeof window === "undefined") {
        return Promise.reject(new Error(translate("portone.not_browser")));
    }
    if (window.PortOne) {
        return Promise.resolve(window.PortOne);
    }
    if (loadingV2) {
        return loadingV2;
    }

    loadingV2 = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = SDK_V2_URL;
        script.async = true;
        script.onload = () => {
            if (window.PortOne) {
                resolve(window.PortOne);
            } else {
                loadingV2 = null;
                reject(new Error(translate("portone.init_failed")));
            }
        };
        script.onerror = () => {
            loadingV2 = null;
            reject(new Error(translate("portone.load_failed")));
        };
        document.head.appendChild(script);
    });

    return loadingV2;
}

/**
 * V2 결제 id. 포트원이 발급하는 것이 아니라 <b>우리가 정한다</b> — 조회·취소·웹훅이
 * 전부 이 값으로 온다. 시도마다 새 값이어야 한다(같은 id 로 두 번 결제할 수 없다).
 * 주문과의 연결은 {@code customData.trackingId} 가 맡는다.
 */
export function newPortOnePaymentId() {
    const uuid = typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return `payment-${uuid}`;
}

/**
 * V2 결제창이 <b>리디렉션</b>으로 끝날 때 돌아올 주소 — 지금 보고 있는 주문 화면(쿼리 · 해시 없이).
 *
 * <p>🐛 2026-10-24 운영: KG이니시스 채널로 바꾸자 "리디렉션 방식으로 결제창을 호출하려면 리디렉션 URL은 필수 입력입니다" 로
 * 결제창이 열리지 않았다. 모바일(그리고 일부 결제 수단은 PC 도)은 결제창을 iframe 이 아니라 페이지 이동으로 띄우고, 끝나면
 * 이 주소로 {@code ?paymentId=…&code=…} 를 붙여 돌려보낸다 — 그때는 {@code requestPayment} 의 반환값이 없다.
 * 돌아온 쪽의 확인은 {@code PortOneReturn} 이 한다. 주소는 {@code https://} · {@code http://} 로 시작해야 한다.
 *
 * <p>[JA] V2 決済画面がリダイレクトで終わるときの戻り先 — 今の注文画面(クエリ · ハッシュなし)。モバイル等ではページ遷移で
 * 決済画面を出し、終わるとこの URL に ?paymentId=… を付けて戻す。戻った側の確認は PortOneReturn。
 */
export function portOneRedirectUrl(loc) {
    const l = loc ?? (typeof window !== "undefined" ? window.location : null);
    return l ? `${l.origin}${l.pathname}` : undefined;
}

/**
 * 리디렉션으로 돌아온 결과 — 우리가 만든 결제 id({@link newPortOnePaymentId} 의 {@code payment-})가 쿼리에 있을 때만.
 * 실패면 {@code code}(와 PG 문구)가 함께 온다.
 *
 * <p>[JA] リダイレクトで戻った結果 — 自前の決済 id(payment-)がクエリにあるときだけ。失敗なら code が付く。
 */
export function portOneReturnOf(searchParams) {
    const paymentId = searchParams?.get("paymentId");
    if (!paymentId || !paymentId.startsWith("payment-")) return null;
    const pick = (k) => searchParams.get(k) ?? undefined;
    return {
        paymentId,
        code: pick("code"),
        message: pick("message"),
        pgCode: pick("pgCode"),
        pgMessage: pick("pgMessage"),
    };
}

/**
 * V2 결제창의 구매자 정보 — 이 주문의 배송지(수취인 · 전화번호)와 로그인 이메일.
 *
 * <p>🐛 2026-10-24 운영: "이니시스 일본 결제의 경우 구매자 이름(customer.fullName …)은 필수 입력입니다" 로 결제창이 열리지 않았다.
 * 예전에는 {@code user.username || user.nickname} 을 실었는데, 로그인 사용자 객체는 JWT 에서 만든 {@code { email, userId, roles }}
 * 뿐이라(util/jwtUser.js) 이름은 <b>언제나 비어</b> 있었다. KG이니시스(국내 PC) · 이니시스 일본결제 모두 이름이 필수다.
 *
 * <p>이름은 {@code fullName} 하나로만 — 이 앱의 배송지는 {@code firstname} 이 <b>성</b>, {@code lastname} 이 <b>이름</b>이라
 * (checkout.address.first_name = 姓) 포트원의 firstName(이름) · lastName(성)과 뜻이 반대다. 둘을 나눠 보내면 뒤바뀐다.
 * 배송지에 이름이 없으면(옛 주소) 이메일 앞부분.
 *
 * <p>[JA] V2 決済画面の購入者情報 — この注文の配送先(受取人 · 電話番号)とログインメール。以前は JWT の利用者オブジェクトに無い
 * username を載せ、名前が常に空だった(イニシス日本決済 · 国内 PC とも必須)。名前は fullName 一つだけ — この アプリの firstname は
 * 姓で、ポートワンの firstName(名)と逆なので分けて送ると入れ替わる。
 */
export function portOneCustomer(order, user) {
    const a = order?.shippingAddress ?? {};
    const name = [a.firstname, a.lastname].map((v) => (v ?? "").trim()).filter(Boolean).join(" ");
    const emailLocal = (user?.email ?? "").split("@")[0];
    return {
        email: user?.email || undefined,
        fullName: name || emailLocal || undefined,
        phoneNumber: a.phoneNumber || undefined,
    };
}

/**
 * 이 청구 통화의 채널에 가상계좌가 있나 — 국내 PG(KRW)만. 이니시스 일본결제(JPY 만)는 카드 · 편의점 · 간편결제뿐이고
 * {@code VIRTUAL_ACCOUNT} 를 보내면 결제창이 열리지 않는다(2026-10-24 운영에서 일본결제 채널을 쓰기로 했다 — docs/49 §6).
 * 편의점 결제는 구매자 이름의 후리가나가 필수인데 배송지에 그 칸이 없어 아직 내지 않는다.
 *
 * <p>[JA] この請求通貨のチャネルにバーチャル口座があるか — 国内 PG(KRW)だけ。イニシス日本決済(JPY のみ)はカード · コンビニ ·
 * 簡単決済だけ。コンビニ決済は購入者名のフリガナが必須だが配送先にその欄がないので、まだ出さない。
 */
export function portOneSupportsVirtualAccount(currency) {
    return String(currency ?? "").toUpperCase() === "KRW";
}

/**
 * 이니시스 일본결제(CBT 유형 JPPG)의 상점 정보 — 그 채널은 상점명(가나 · 영문 · 약어) · 담당 · 이메일 · 연락처 · 영업시간이
 * 모두 필수다(2026-10-24 운영 채널이 JPPG). 다른 채널(국내 KRW)에는 싣지 않는다.
 *
 * <p>상점명 · 연락처는 공개 값이라 여기 둔다(번들에 박힌다). 이메일은 빌드 변수 {@code REACT_APP_PORTONE_STORE_EMAIL}
 * (공개 값 — 저장소 Variables, docs/49 §6). 비어 있으면 그 칸을 빼고 보낸다 — 결제창이 이유를 말한다.
 * 연락처는 숫자만(포트원 예시가 숫자만이다). 영업시간은 온라인 상점이라 하루 종일(00:00 ~ 23:59 — 허용 범위의 끝).
 *
 * <p>[JA] イニシス日本決済(CBT 種別 JPPG)の店舗情報 — 店舗名(カナ · 英字 · 略称) · 担当 · メール · 連絡先 · 営業時間がすべて必須。
 * 国内(KRW)チャネルには載せない。メールはビルド変数 REACT_APP_PORTONE_STORE_EMAIL(公開値)。連絡先は数字のみ。
 */
export const PORTONE_JP_STORE = Object.freeze({
    storeName: "アマス",
    storeNameKana: "アマス",
    storeNameEn: "Amasu",
    storeNameShort: "アマス",
    contactName: "アマス",
    phoneNumber: "0810700000001",
    openingHours: Object.freeze({ open: "00:00", close: "23:59" }),
});

/** 이 청구 통화에 실을 상점 정보 — JPY(이니시스 일본결제)만, 그 밖이면 undefined. */
// [JA] この請求通貨で載せる店舗情報 — JPY(イニシス日本決済)だけ、それ以外は undefined。
export function portOneStoreDetails(currency, email) {
    if (String(currency ?? "").toUpperCase() !== "JPY") return undefined;
    const e = (email ?? "").trim();
    return { ...PORTONE_JP_STORE, openingHours: { ...PORTONE_JP_STORE.openingHours }, ...(e ? { email: e } : {}) };
}

/** 돌아온 쿼리에서 지울 키 — 남기면 새로고침마다 다시 확인하고, 결제 id 가 방문 기록에 남는다. */
export const PORTONE_RETURN_KEYS = ["paymentId", "code", "message", "pgCode", "pgMessage", "transactionType", "txId"];

/**
 * 어느 버전으로 빌드됐나. Store ID 가 있으면 V2, 아니면 식별코드가 있을 때 V1.
 *
 * <p>⚠️ 기본값을 {@code env = process.env} 로 두지 않는다. 번들러는 {@code process.env.이름}
 * 꼴만 값으로 바꾸고, 맨몸의 {@code process.env} 는 그대로 남겨 브라우저에서
 * {@code process is not defined} 가 된다(webpack.env.js). 이름을 하나씩 읽어 넘긴다.
 */
export function portOneVersion(env) {
    const e = env ?? {
        REACT_APP_PORTONE_STORE_ID: process.env.REACT_APP_PORTONE_STORE_ID,
        REACT_APP_PORTONE_IMP_CODE: process.env.REACT_APP_PORTONE_IMP_CODE,
    };
    if (e.REACT_APP_PORTONE_STORE_ID) return "v2";
    if (e.REACT_APP_PORTONE_IMP_CODE) return "v1";
    return null;
}
