import { Link } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 화면이 예외로 죽었을 때 대신 그린다.
 *
 * <p>🐛 예전에는 이랬다:
 *
 * <pre>
 *   &lt;div role="alert"&gt;
 *     &lt;p&gt;Something went wrong:&lt;/p&gt;
 *     &lt;pre style={{ color: 'red' }}&gt;{error.message}&lt;/pre&gt;
 *     &lt;button onClick={resetErrorBoundary}&gt;Try again&lt;/button&gt;
 *   &lt;/div&gt;
 * </pre>
 *
 * <p>세 가지가 문제였다.
 *
 * <ul>
 *   <li><b>메시지가 사용자에게 아무 의미가 없다.</b> axios 오류의 {@code message} 는
 *       "Request failed with status code 401" 이다. 정작 서버가 보낸 설명
 *       ({@code response.data.message})은 버려졌다.</li>
 *   <li><b>나갈 길이 없다.</b> "Try again" 은 같은 화면을 다시 그릴 뿐이라 원인이
 *       그대로면 똑같이 죽는다. 헤더도 링크도 없어 홈으로 갈 방법이 없다.</li>
 *   <li><b>무엇이든 똑같이 보인다.</b> 권한이 없는 것과 서버가 죽은 것은 사용자가 할 수
 *       있는 일이 다르다.</li>
 * </ul>
 *
 * <p>이걸 손본 이유: 인터셉터가 401·500·503 을 더 이상 "로그아웃" 이나 "홈으로 이동" 으로
 * 삼키지 않고 호출부로 돌려주기 시작했다(TokenRefresher). 그 오류들이 결국 여기로 오므로,
 * 여기가 읽을 만해야 그 변경이 의미가 있다.
 */
const ErrorFallback = ({ error, resetErrorBoundary }) => {
    const { t } = useTranslation();
    const status = error?.response?.status;
    const serverMessage = error?.response?.data?.message;

    const { title, detail, showSignIn } = describe(status, serverMessage, error, t);

    return (
        <div
            role="alert"
            className="min-h-[60vh] flex flex-col items-center justify-center gap-4 px-6 text-center"
        >
            <h1 className="text-2xl font-bold text-amazon-blue_dark">{title}</h1>
            <p className="text-slate-600 max-w-md">{detail}</p>

            <div className="flex flex-wrap items-center justify-center gap-2 mt-2">
                <button
                    type="button"
                    onClick={resetErrorBoundary}
                    className="px-5 py-2 rounded-full bg-amazon-orange font-semibold text-amazon-blue_dark hover:brightness-95"
                >
                    {t("error.retry")}
                </button>

                <Link
                    to="/"
                    onClick={resetErrorBoundary}
                    className="px-5 py-2 rounded-full border border-slate-300 hover:bg-slate-50"
                >
                    {t("error.go_home")}
                </Link>

                {showSignIn && (
                    <Link
                        to="/signin"
                        onClick={resetErrorBoundary}
                        className="px-5 py-2 rounded-full border border-slate-300 hover:bg-slate-50"
                    >
                        {t("error.fallback.signin")}
                    </Link>
                )}
            </div>

            {/*
              * 기술적인 내용은 접어 둔다. 사용자에게는 소음이지만, 문의를 받는 쪽에는
              * 이 한 줄이 전부다 — 아예 숨기면 "뭔가 잘못됐대요" 말고는 남는 게 없다.
              */}
            <details className="mt-4 text-xs text-slate-400">
                <summary className="cursor-pointer">{t("error.fallback.tech_info")}</summary>
                <pre className="mt-2 whitespace-pre-wrap break-all text-left">
                    {[status && `HTTP ${status}`, error?.message].filter(Boolean).join(" · ")}
                </pre>
            </details>
        </div>
    );
};

/**
 * 사용자가 할 수 있는 일이 무엇인지로 나눈다.
 *
 * <p>서버가 설명을 보냈으면 그것을 쓴다 — 우리가 만든 일반론보다 구체적이다.
 */
function describe(status, serverMessage, error, t) {
    if (status === 401 || status === 403) {
        return {
            title: t("error.fallback.forbidden.title"),
            // 로그아웃된 것이 아니라 이 기능에 대한 권한이 없는 경우다(TokenRefresher 주석).
            detail: serverMessage
                ?? t("error.fallback.forbidden.detail"),
            showSignIn: true,
        };
    }
    if (status === 404) {
        return {
            title: t("error.fallback.not_found.title"),
            detail: serverMessage ?? t("error.fallback.not_found.detail"),
            showSignIn: false,
        };
    }
    if (status === 429) {
        return {
            title: t("error.fallback.too_many.title"),
            detail: t("error.fallback.too_many.detail"),
            showSignIn: false,
        };
    }
    if (status === 503) {
        return {
            title: t("error.fallback.unavailable.title"),
            detail: t("error.fallback.unavailable.detail"),
            showSignIn: false,
        };
    }
    if (status >= 500) {
        return {
            title: t("error.fallback.generic.title"),
            detail: serverMessage ?? t("error.fallback.server.detail"),
            showSignIn: false,
        };
    }
    if (status >= 400) {
        return {
            title: t("error.fallback.bad_request.title"),
            detail: serverMessage ?? t("error.fallback.bad_request.detail"),
            showSignIn: false,
        };
    }

    // 네트워크 오류·타임아웃, 또는 렌더 도중의 자바스크립트 오류.
    const timedOut = error?.code === "ECONNABORTED";
    return {
        title: timedOut ? t("error.fallback.timeout.title") : t("error.fallback.generic.title"),
        detail: timedOut
            ? t("error.fallback.timeout.detail")
            : t("error.fallback.render.detail"),
        showSignIn: false,
    };
}

export default ErrorFallback;
