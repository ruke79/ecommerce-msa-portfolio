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
