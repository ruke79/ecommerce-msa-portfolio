import { ChevronRightIcon } from "@heroicons/react/24/solid";
import { Form, Formik } from "formik";
import { useNavigate, useLocation, Link, useSearchParams } from "react-router-dom";
import { useRef, useState, useEffect } from "react";
import SocialLogin from "../../components/auth/SocialLogin";
import ResendVerification from "components/auth/ResendVerification";
import { useTranslation } from "i18n/I18nProvider";
import BrandMark from "components/common/BrandMark";
import { BRAND_NAME } from "constants/brand";
import LoginInput from "./LoginInput";
import * as Yup from "yup";
import ButtonInput from "./ButtonInput";
import { useAuthContext, STATUS } from "../../store/AuthContext";
import toast from "react-hot-toast";
import api, { queryClient, getPasskeyLoginOptions, loginWithPasskey } from 'util/api'
import { credentialToJSON, isPasskeySupported, isUserCancel, toRequestOptions } from 'util/passkey'
import { API_BASE } from 'util/apiBase'
import { alreadyToasted, loginErrorMessage } from 'util/loginError'
import { loginResultMessage } from 'util/loginResult'
import { jwtDecode } from "jwt-decode";
import { toUser } from "util/jwtUser";
import { useMutation } from '@tanstack/react-query';
import { clearPendingLogout } from "util/pendingLogout";
import { useErrorBoundary } from "react-error-boundary";



const apiUrl = API_BASE;

// 오류를 잡지 않는다. 예전에는 catch 가 로그만 찍고 끝나서 함수가 undefined 를 반환했고,
// useMutation 은 그것을 **성공**으로 받았다. 그래서 비밀번호가 틀려도 onError 는 한 번도
// 실행되지 않았고, onSuccess 안에서 `response.headers` 접근이 터진 뒤 "Login Failed.
// Please retry." 라는 뭉뚱그린 메시지만 떴다. 서버가 알려준 진짜 이유는 콘솔에만 남았다.
const signin = (user) => api.post("/user-service/api/auth/public/signin", user);

const LOGIN_QUERY_KEY = 'login';


// 소셜 로그인 실패 시 서버가 ?error= 로 돌려보내는 코드 → 문구 키.
// 문구가 아니라 키를 담는다 — 모듈 로드 때 한 번 평가되는 상수에 문구를 넣으면 언어를 바꿔도 그대로다.
// 서버 쪽 짝: OAuth2FailureHandler.SHOWN_CODES — 거기서 허용한 코드만 이유를 따로 안내한다.
const OAUTH_ERROR_KEYS = {
    oauth_user_not_found: "user.signin.oauth_user_not_found",
    email_required: "user.signin.oauth_email_required",
    unverified_email: "user.signin.oauth_unverified_email",
    access_denied: "user.signin.oauth_cancelled",
    account_restricted: "user.signin.account_restricted",   // OAuth2SuccessHandler — 관리자가 잠그거나 만료한 계정
};

const SignInPage = () => {
    const { t } = useTranslation();
    const [step, setStep] = useState(1);

    const [needHelp, setNeedHelp] = useState(false);
    const [searchParams] = useSearchParams();

    // 소셜 로그인의 2FA 단계인가. 성공 핸들러가 PENDING_2FA(httpOnly) 쿠키를 심고
    // ?oauth2fa=1 로 보낸다 — 토큰은 쿠키에 있으므로 화면은 코드만 물어보면 된다.
    const isOauth2fa = searchParams.get("oauth2fa") === "1";

    // 소셜 로그인 실패는 XHR 응답이 아니라 **리다이렉트 쿼리**로 돌아온다 — 여기서
    // 받아 주지 않으면 사용자는 로그인 화면으로 조용히 튕겨 이유를 모른다.
    useEffect(() => {
        const code = searchParams.get("error");
        if (code) {
            toast.error(t(OAUTH_ERROR_KEYS[code] ?? "user.signin.oauth_failed"));
        }
        if (searchParams.get("oauth2fa") === "1") {
            setStep(2);
        }
        // 🐛 메일 인증에 성공하면 서버가 `/signin?verified=1` 로 보내는데 **아무도 읽지
        //    않았다.** 사용자는 링크를 누른 뒤 아무 설명 없는 로그인 화면을 보고, 인증이
        //    된 것인지 아닌지 알 수 없었다. 인증은 한 번뿐인 행동이라 확인이 더 중요하다.
        if (searchParams.get("verified") === "1") {
            toast.success(t("auth.signin.verified"), { id: "verified" });
        }
        // t 는 언어가 바뀔 때만 새로 만들어진다(useCallback). 빠뜨리면 언어를 바꾼 직후
        // 이 효과가 옛 언어의 문구를 띄운다.
    }, [searchParams, t]);
    // ⚠️ 폼 값은 **Formik 이 소유한다.** 예전에는 여기 별도의 user 상태를 두고 LoginInput 에
    // onChange 를 내려보내 Formik 의 onChange 를 덮어썼다(LoginInput 이 {...field} 뒤에
    // {...props} 를 펼치기 때문). 그러면 값의 출처가 둘이 되고, enableReinitialize 가
    // 키 입력마다 Formik 을 초기화하면서 **입력한 글자가 실제로 유실됐다** —
    // "a@example.com" 을 치면 "aeapecm" 이 남는다. 상태를 한 곳으로 모아 해결한다.
    /**
     * 실패 사유.
     *
     * <p>🐛 예전에는 setter 가 없는 `useState("")` 라 <b>영원히 빈 문자열</b>이었다 —
     * 아래 `{login_error && ...}` 블록은 한 번도 그려지지 않는 죽은 코드였다. 실패는
     * 토스트로만 알렸는데, 토스트는 몇 초 뒤 사라지고 나면 사용자는 아무 흔적도 없는
     * 폼을 보게 된다. 특히 "메일 인증을 해 주세요" 처럼 <b>읽고 행동해야 하는</b> 안내는
     * 사라지면 안 된다.
     */
    const [login_error, setLoginError] = useState("");

    /**
     * 메일 인증이 안 된 계정이라 거부됐는가.
     *
     * <p>🐛 로그인 실패 문구는 "확인 메일 재발송을 이용해 주세요" 라고 안내하는데
     * <b>이용할 화면이 없었다.</b> 서버의 재발송 엔드포인트는 구현돼 있었지만 프론트에서
     * 부르는 곳이 한 군데도 없었다. 안내만 하고 길은 없는 상태였다.
     *
     * <p>판단은 서버가 보낸 {@code code} 로 한다. 한국어 문구를 비교하면 문구를 다듬는
     * 순간 조용히 깨지고, 사용자는 재발송 경로가 사라진 것을 알 방법이 없다.
     */
    const [needsVerification, setNeedsVerification] = useState(false);

    
    

    const { login, setAuthenticationStatus } = useAuthContext();

    const navigate = useNavigate();
    const location = useLocation();

    /**
     * 로그인 후 어디로 갈 것인가.
     *
     * <p>🐛 예전에는 무조건 `navigate('/')` 였다. /checkout 에서 세션이 끊겨 로그인하러
     * 갔다 오면 홈으로 떨어져, 장바구니부터 다시 시작해야 했다. ProtectedRoute 가
     * `state.from` 에 막힌 위치를 넘겨 준다.
     *
     * <p>넘어온 경로가 **내부 경로인지 확인한다.** 그냥 믿고 navigate 하면
     * `/signin` 링크에 외부 주소를 심어 로그인 직후 다른 사이트로 보내는 열린
     * 리다이렉트가 된다. `/` 로 시작하되 `//`(프로토콜 상대 URL)는 아닌 것만 받는다.
     */
    const redirectTo = (() => {
        const from = location.state?.from;
        const path = typeof from === "string" ? from : from?.pathname;
        if (typeof path !== "string") return "/";
        if (!path.startsWith("/") || path.startsWith("//")) return "/";
        // 역슬래시·제어 문자도 막는다 — 브라우저는 `/\evil.com` 을 `//evil.com`(외부)으로 읽는다. react-router 6 의 열린
        // 리다이렉트 권고(GHSA-wrjc-x8rr-h8h6, 수정은 7.18)가 바로 이 우회다. 앱의 내부 경로에는 둘 다 쓸 일이 없다.
        if (/[\\\u0000-\u001f]/.test(path)) return "/";
        if (path.startsWith("/signin") || path.startsWith("/register")) return "/";
        const search = typeof from === "object" ? (from.search ?? "") : "";
        return `${path}${search}`;
    })();




    // 🔴 2FA 는 서버가 강제한다. 예전에는 서버가 비밀번호만 맞으면 액세스 토큰을 먼저 주고, 이 화면이 그 토큰을
    //    들고 있다가 코드 확인 뒤에야 login() 을 불렀다 — 막은 것은 화면뿐이라 응답 헤더의 토큰을 직접 쓰면
    //    2FA 를 건너뛰었다. 이제 서버는 2FA 계정에 토큰 대신 대기 쿠키(PENDING_2FA, httpOnly)만 심고
    //    code=two_factor_required 로 답한다. 비밀번호 경로도 소셜 경로와 같은 끝이다.
    const [code2fa, setCode2fa] = useState("");
    // 두 번째 단계가 무엇인가 — "totp"(2단계 인증 앱) 또는 "email"(처음 보는 기기 + 공격 신호일 때 메일로 보낸 코드, docs/68 §19).
    // [JA] 2 段階目が何か — "totp"(認証アプリ)または "email"(未知の端末 + 攻撃信号のときメールで送ったコード)。
    const [checkMode, setCheckMode] = useState("totp");
    const [verifying, setVerifying] = useState(false);
    const [twoFactorError, setTwoFactorError] = useState("");

    const { mutate, isPending } = useMutation({
        mutationFn: signin,
        // throwOnError 를 켜면 실패가 ErrorBoundary 로 올라가 로그인 화면 자체가 사라진다.
        // 로그인 실패는 이 화면에서 처리해야 하는 정상적인 흐름이므로 여기서 잡는다.
        throwOnError: false,
        onSuccess: (response) => {
            setLoginError("");
            setNeedsVerification(false);
            if (response?.data?.code === "two_factor_required") {
                setCheckMode("totp");
                setStep(2);
            } else if (response?.data?.code === "login_check_required") {
                // 비밀번호는 맞았지만 서버가 토큰 대신 확인 대기 쿠키만 줬다 — 메일로 온 코드를 받는다.
                setCheckMode("email");
                setStep(2);
            } else {
                const access = response.headers['access'];
                handleSuccessfulLogin(response, jwtDecode(access));
            }
            // 오타였다: `querykey`(소문자 k)는 react-query 가 모르는 키라 필터가 통째로
            // 무시되고 **모든 쿼리**가 무효화됐다. 로그인할 때마다 장바구니·주문·상품까지
            // 전부 다시 불러왔다.
            queryClient.invalidateQueries({ queryKey: [LOGIN_QUERY_KEY] });
        },
        onError: (error) => {
            // 서버가 보낸 사유를 그대로 쓴다 — 잠금까지 남은 시간이나 메일 인증 안내처럼
            // 서버만 아는 사실이 담겨 있다(util/loginError 주석).
            const message = loginErrorMessage(error);
            setLoginError(message);
            // 서버가 붙여 보내는 코드로만 판단한다(AuthLoginFilter.EMAIL_NOT_VERIFIED_CODE).
            // 이 코드는 **비밀번호가 맞은 뒤에만** 나오므로, 재발송 폼이 뜨는 것 자체가
            // 가입 여부를 남에게 알려주지 않는다.
            setNeedsVerification(error?.response?.data?.code === "email_not_verified");
            // 인라인으로 남기고 토스트로도 한 번 알린다. 503 은 인터셉터가 이미 띄우므로
            // 같은 말을 두 번 쌓지 않는다.
            if (!alreadyToasted(error)) {
                toast.error(message, { id: "loginFailed" });
            }
            setAuthenticationStatus(STATUS.FAILED);
        }
    });

    /**
     * 2단계 인증 코드 확인 — 비밀번호 경로와 소셜 경로가 같다.
     *
     * <p>신원은 PENDING_2FA httpOnly 쿠키가 나른다(withCredentials 로 같이 간다). 화면은 토큰을 만지지 않는다.
     * 통과하면 서버가 REFRESH 쿠키를 심으므로, 전체 리로드로 앱을 다시 띄우면 TokenRefresher 가 그 쿠키로
     * 세션을 복구한다 — 정상 로그인과 같은 끝. 가려던 곳(redirectTo)은 내부 경로만 남아 있다.
     */
    // 패스키 로그인(user-service docs/68 §20) — 이메일을 먼저 묻지 않는다. 기기가 이 사이트의 계정 목록을 보여 준다.
    // 통과하면 서버가 REFRESH 쿠키를 심는다 — 2FA 확인과 같은 끝(전체 리로드).
    // [JA] パスキーログイン — メールを先に聞かない。通過したらサーバーが REFRESH Cookie を付ける(2FA 確認と同じ終わり方)。
    const [passkeyBusy, setPasskeyBusy] = useState(false);
    const [passkeyError, setPasskeyError] = useState("");
    const passkeyHandler = async () => {
        setPasskeyBusy(true);
        setPasskeyError("");
        try {
            const options = await getPasskeyLoginOptions();
            const credential = await navigator.credentials.get({ publicKey: toRequestOptions(options) });
            await loginWithPasskey(credentialToJSON(credential));
            window.location.href = redirectTo;
        } catch (error) {
            // 기기 창을 닫은 것은 오류가 아니다.
            if (!isUserCancel(error)) {
                setPasskeyError(error?.response?.data?.message ?? t("user.signin.passkey.failed"));
            }
            setPasskeyBusy(false);
        }
    };

    const verify2faHandler = async () => {
        setVerifying(true);
        setTwoFactorError("");
        try {
            const path = checkMode === "email"
                ? "/user-service/api/auth/public/verify-login-check"
                : "/user-service/api/auth/public/verify-2fa";
            await api.post(`${path}?code=${encodeURIComponent(code2fa)}`);
            window.location.href = isOauth2fa ? "/" : redirectTo;
        } catch (error) {
            setTwoFactorError(
                error?.response?.data?.message ?? t("user.signin.2fa.failed")
            );
            setCode2fa("");
            setVerifying(false);
        }
    };

    // 화면의 다른 문구는 전부 한국어인데 검증 메시지만 영어였고, 그중 하나는 오타였다
    // ("Please endter a valid address") 였다.
    const loginValidation = Yup.object({
        email: Yup.string()
            .required(t("user.signin.email_required"))
            .email(t("user.signin.email_invalid")),
        password: Yup.string().required(t("user.validation.password_required")),
    });

    const handleSuccessfulLogin = (response, decodedToken) => {
        const accessToken = response.headers['access'];

        // JWT 의 role 클레임을 정확히 읽는다(util/jwtUser 주석 참조).
        const user = toUser(decodedToken, accessToken);
        //localStorage.setItem("access_token", accessToken);
        //localStorage.setItem("USER", JSON.stringify(user));

        //store the token on the context state  so that it can be shared any where in our application by context provider       

        // 새로 로그인했다 — 마무리하지 못한 예전 로그아웃 표시가 다음 접속에서 이 로그인을 끊지 않게(util/pendingLogout).
        clearPendingLogout();
        login(user, accessToken, decodedToken.exp);
        //updateToken(accessToken);
        navigate(redirectTo, { replace: true });
        toast.success(loginResultMessage(response.data, t));

    };

    // Formik 이 검증을 통과시킨 값을 그대로 받는다.
    const signInHandler = (values) => {
        mutate({ email: values.email, password: values.password });
    };


    return (
        <>
            <div className="flex flex-col mx-auto w-full px-4 sm:w-3/5 md:w-3/5 lg:w-2/5  pt-8 pb-16">
                <div className="mx-auto my-2">
                    <Link to="/">
                        {/* 예전에는 Amazon 로고 png 였다 — 계정 생성·로그인 화면에
                            남의 로고를 거는 것이라 교체했다(constants/brand.js 주석). */}
                        <BrandMark variant="dark" size="lg" className="pt-2" />
                    </Link>
                </div>
                {/* 2단계 인증 화면. 예전에는 setStep(2) 만 하고 step 을 읽는 곳이 아무 데도
                    없어서, 2FA 를 켠 사용자는 비밀번호를 맞게 넣어도 화면이 그대로였다 —
                    아무 반응 없이 로그인이 안 되는 것처럼 보였다. */}
                {step === 2 ? (
                    <div className="flex flex-col p-4 my-4 bg-white rounded border space-y-4">
                        <h3 className="text-xl font-bold">
                            {t(checkMode === "email" ? "user.signin.check.title" : "user.signin.2fa.title")}
                        </h3>
                        <p className="text-sm text-slate-600">
                            {t(checkMode === "email" ? "user.signin.check.hint" : "user.signin.2fa.hint")}
                        </p>
                        <input
                            type="text"
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            maxLength={6}
                            value={code2fa}
                            autoFocus
                            onChange={(e) => setCode2fa(e.target.value.replace(/\D/g, ""))}
                            onKeyDown={(e) => {
                                if (e.key === "Enter" && code2fa.length === 6 && !verifying) {
                                    verify2faHandler();
                                }
                            }}
                            className="w-full tracking-[0.5em] text-center text-2xl border rounded py-2"
                            aria-label={t("user.signin.2fa.code_label")}
                        />
                        {twoFactorError && (
                            <p className="text-red-500 text-sm" role="alert">{twoFactorError}</p>
                        )}
                        <ButtonInput
                            type="button"
                            text={verifying ? t("user.signin.2fa.verifying") : t("user.signin.2fa.confirm")}
                            disabled={verifying || code2fa.length !== 6}
                            onClick={verify2faHandler}
                        />
                        <button
                            type="button"
                            className="text-xs text-blue-500 hover:underline"
                            onClick={() => {
                                // 소셜 경로면 쿼리(oauth2fa=1)까지 지워야 새로고침해도
                                // 2FA 화면으로 돌아오지 않는다.
                                if (isOauth2fa) {
                                    window.location.href = "/signin";
                                    return;
                                }
                                // 처음으로 돌아간다. 대기 쿠키는 5분 뒤 스스로 사라지고, 새 로그인이
                                // 덮어쓴다.
                                setCode2fa("");
                                setTwoFactorError("");
                                setStep(1);
                            }}
                        >
                            {t("user.signin.2fa.other_account")}
                        </button>
                    </div>
                ) : (
                <div className="flex flex-col p-4 my-4 bg-white rounded border space-y-4">
                    <h3 className="text-xl font-bold">{t("user.signin.heading")}</h3>
                    <Formik
                        initialValues={{ email: "", password: "" }}
                        validationSchema={loginValidation}
                        onSubmit={signInHandler}
                    >
                        {(form) => (
                            <Form >
                                <LoginInput
                                    id="input-email"
                                    type="text"
                                    icon="email"
                                    name="email"
                                    placeholder={t("user.signin.email_placeholder")}
                                />

                                <LoginInput
                                    id="input-password"
                                    type="password"
                                    icon="password"
                                    name="password"
                                    placeholder={t("user.signin.password_placeholder")}
                                />
                                <ButtonInput type="submit" text={t("user.signin.submit")}  disabled={isPending}  // isPending 상태를 사용하여 버튼을 비활성화합니다.
                              />
                            </Form>
                        )}
                    </Formik>

                    <p className="text-xs mt-2">
                        {t("user.terms_notice", { brand: BRAND_NAME })}
                    </p>
                    {/* 실패 사유는 폼 바로 아래에 남긴다. 토스트만 쓰면 몇 초 뒤 사라지고,
                        "메일 인증 링크를 눌러 주세요" 처럼 읽고 행동해야 하는 안내가 그대로
                        증발한다. role="alert" 라 스크린리더도 즉시 읽는다. */}
                    {login_error && (
                        <p
                            role="alert"
                            className="text-sm text-red-600 bg-red-50 border border-red-200 rounded p-2"
                        >
                            {login_error}
                        </p>
                    )}

                    {/* 안내만 하고 길을 주지 않으면 사용자는 갇힌다. 링크는 24시간이면
                        만료되고 같은 주소로 재가입도 막히므로, 재발송이 유일한 출구다. */}
                    {needsVerification && (
                        <div className="rounded border border-slate-200 bg-slate-50 p-3">
                            <ResendVerification />
                        </div>
                    )}

                    <div
                        onClick={() => setNeedHelp(!needHelp)}
                        className="text-xs flex flex-col cursor-pointer"
                    >
                        <div className="flex items-center">
                            <ChevronRightIcon
                                className={`h-3 text-gray-500" ${needHelp && "rotate-90"
                                    }`}
                            />
                            <span className="text-blue-500 hover:text-amazon-orange hover:underline ml-1">
                                {t("auth.signin.need_help")}
                            </span>
                        </div>
                        {needHelp && (
                            <div className="flex flex-col ml-4 mt-2 space-y-2">
                                <Link
                                    to="/forgot"
                                    className="text-blue-500 hover:text-amazon-orange hover:underline"
                                >
                                    {t("auth.signin.forgot_password")}
                                </Link>
                                {/* 로그인을 시도해 보기 전에도 찾을 수 있어야 한다 —
                                    메일이 아예 안 온 사람은 무엇을 눌러야 할지 모른다. */}
                                <Link
                                    to="/verification-failed"
                                    className="text-blue-500 hover:text-amazon-orange hover:underline"
                                >
                                    {t("auth.signin.no_verification_mail")}
                                </Link>
                                {/* react-router 의 Link 는 `to` 를 받는다. `href` 를 주면
                                    to 가 undefined 라 아무 데도 가지 않는 링크가 된다. */}
                                <Link
                                    to="/"
                                    className="text-blue-500 hover:text-amazon-orange hover:underline"
                                >
                                    {t("user.signin.other_issues")}
                                </Link>
                            </div>
                        )}
                    </div>

                    {isPasskeySupported() && (
                        <div className="flex flex-col space-y-1">
                            <button
                                type="button"
                                onClick={passkeyHandler}
                                disabled={passkeyBusy}
                                className="w-full border rounded py-2 text-sm hover:bg-slate-50 disabled:opacity-40"
                            >
                                {t("user.signin.passkey.button")}
                            </button>
                            {passkeyError && (
                                <p className="text-red-500 text-sm" role="alert">{passkeyError}</p>
                            )}
                        </div>
                    )}

                    <SocialLogin />
                </div>
                )}

                <div className="flex flex-col mt-3">
                    <span
                        className="pt-1 relative flex justify-center text-sm 
                before:left-1 before:top-[50%] before:absolute before:bg-slate-200 before:h-[1px] before:w-[20%] sm:before:w-[25%] md:before:w-[31%]
                after:right-1 after:top-[50%] after:absolute after:bg-slate-200 after:h-[1px] after:w-[20%] sm:after:w-[25%] md:after:w-[31%]"
                    >
                        {t("user.signin.new_to_brand", { brand: BRAND_NAME })}
                    </span>
                    <Link
                        to="/register"
                        className="flex items-center justify-center w-full mt-4 button-orange  py-[0.5rem] text-sm text-gray-900 active:from-amazon-orange active:to-yellow-200 "
                    >
                        {t("user.signin.create_account", { brand: BRAND_NAME })}
                    </Link>
                </div>
            </div>

        </>

    );
}

export default SignInPage;