import { useState } from "react";
import { EnvelopeIcon } from "@heroicons/react/24/outline";

import { postRequest } from "util/api";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 가입 확인 메일 재발송.
 *
 * <p>🐛 <b>서버는 이 기능을 갖고 있었는데 부를 방법이 없었다.</b>
 * {@code POST /api/auth/public/resend-verification} 은 구현돼 있었지만 프론트 어디에서도
 * 부르지 않았다(검색 결과 0건). 그래서:
 *
 * <ul>
 *   <li>로그인 실패 문구가 "확인 메일 재발송을 이용해 주세요" 라고 안내하는데
 *       <b>이용할 화면이 없었다.</b></li>
 *   <li>메일이 스팸함에 가거나 SMTP 가 실패하면(실제로 일어났다) 24시간 뒤 토큰이 만료되고
 *       그 계정은 <b>막다른 길</b>이 된다 — 같은 주소로 재가입도 막힌다.</li>
 * </ul>
 *
 * <p><b>서버 응답은 어떤 경우에도 같다.</b> 가입되지 않은 주소든, 이미 인증된 주소든,
 * 미인증 주소든 200 과 같은 문구가 온다 — 다르게 답하면 이 화면이 <b>가입 여부 확인 도구</b>가
 * 되기 때문이다. 그래서 여기서도 "보냈는지"를 추측해 보여주지 않고 서버 문구를 그대로 쓴다.
 * 스팸함을 함께 안내하는 이유이기도 하다: 우리도 도착 여부를 모른다.
 */
const ResendVerification = ({ defaultEmail = "", className = "" }) => {
    const { t } = useTranslation();
    const [email, setEmail] = useState(defaultEmail);
    const [sending, setSending] = useState(false);
    const [done, setDone] = useState("");
    const [error, setError] = useState("");

    const submit = async (e) => {
        e.preventDefault();
        if (sending) return;

        setSending(true);
        setError("");
        setDone("");

        try {
            const { data } = await postRequest(
                "/user-service/api/auth/public/resend-verification",
                { email }
            );
            setDone(data?.message ?? t("auth.resend.sent"));
        } catch (err) {
            // 게이트웨이의 속도 제한(429)에 걸리는 경우가 현실적으로 가장 흔하다.
            // 그때 "실패했습니다" 만 띄우면 사용자는 계속 다시 누른다.
            setError(
                err?.response?.status === 429
                    ? t("auth.resend.rate_limited")
                    : err?.response?.data?.message ?? t("auth.resend.failed")
            );
        } finally {
            setSending(false);
        }
    };

    return (
        <form onSubmit={submit} className={`flex flex-col gap-2 ${className}`}>
            <label htmlFor="resend-email" className="text-sm font-semibold">
                {t("auth.resend.email_label")}
            </label>
            <div className="flex gap-2">
                <input
                    id="resend-email"
                    type="email"
                    required
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    className="flex-1 min-w-0 rounded border px-3 py-2 text-sm"
                />
                <button
                    type="submit"
                    disabled={sending}
                    className="flex shrink-0 items-center rounded border px-3 py-2 text-sm font-semibold
                               hover:bg-slate-100 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    <EnvelopeIcon className="mr-1 h-4 w-4" />
                    {sending ? t("common.sending") : t("auth.resend.submit")}
                </button>
            </div>

            {/* role=status 로 둔다 — 화면을 보지 않는 사용자에게도 결과가 읽힌다. */}
            <p role="status" className="text-sm text-emerald-700">
                {done}
            </p>
            {error && (
                <p role="alert" className="text-sm text-red-600">
                    {error}
                </p>
            )}
        </form>
    );
};

export default ResendVerification;
