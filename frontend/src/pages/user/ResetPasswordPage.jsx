import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";

import BrandMark from "components/common/BrandMark";
import { postRequest } from "util/api";
import { useDocumentTitle } from "hook/useDocumentTitle";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 비밀번호 재설정 — 메일의 링크가 도착하는 화면.
 *
 * <p>🐛 <b>이 화면이 없었다.</b> 서버는 재설정 메일에
 * {@code {frontendUrl}/reset-password?token=…} 을 실어 보내는데 프론트 전체에
 * {@code reset-password} 문자열이 0건이었다 — 링크를 누르면 라우터 오류 화면이 떴다.
 * 비밀번호 찾기는 <b>처음부터 끝까지 동작한 적이 없다.</b> `/forgot`·`/verification-failed`
 * 와 같은 종류의 구멍이다.
 *
 * <p>토큰은 주소에서 읽어 <b>바디로</b> 보낸다. 쿼리스트링은 nginx 액세스 로그·프록시·
 * 브라우저 히스토리에 남는데, 이 토큰은 그 자체로 계정을 가져갈 수 있는 자격증명이다.
 * 링크(GET)로 오는 것은 어쩔 수 없지만 그것을 쓰는 요청까지 같은 자리에 둘 이유는 없다.
 */

/** 서버의 비밀번호 정책과 맞춘다. 숫자를 문구 안에 적어 두면 정책이 바뀔 때 한쪽만 바뀐다. */
const MIN_LENGTH = 6;

const ResetPasswordPage = () => {
    const [searchParams] = useSearchParams();
    const token = searchParams.get("token");
    const navigate = useNavigate();
    const { t } = useTranslation();

    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [sending, setSending] = useState(false);
    const [error, setError] = useState("");
    const [done, setDone] = useState("");

    useDocumentTitle(t("auth.reset.doc_title"));

    const submit = async (e) => {
        e.preventDefault();
        if (sending) return;

        // 서버도 같은 것을 보지만, 여기서 막으면 왕복 한 번과 토큰 한 번의 소진을 아낀다.
        // 재설정 토큰은 1회용이라 잘못된 입력으로 태우면 메일을 다시 받아야 한다.
        if (password.length < MIN_LENGTH) {
            setError(t("auth.reset.too_short", { min: MIN_LENGTH }));
            return;
        }
        if (password !== confirm) {
            setError(t("auth.reset.mismatch"));
            return;
        }

        setSending(true);
        setError("");

        try {
            const { data } = await postRequest("/user-service/api/auth/public/reset-password", {
                token,
                newPassword: password,
            });
            setDone(data?.message ?? t("auth.reset.done"));
            // 바로 넘기지 않고 잠깐 보여준다 — 성공을 못 보고 넘어가면 바뀐 건지 알 수 없다.
            setTimeout(() => navigate("/signin"), 1500);
        } catch (err) {
            setError(
                err?.response?.data?.message ?? t("auth.reset.failed")
            );
        } finally {
            setSending(false);
        }
    };

    return (
        <div className="mx-auto flex w-full flex-col px-4 pb-16 pt-8 sm:w-3/5 md:w-3/5 lg:w-2/5">
            <div className="mx-auto my-2">
                <Link to="/">
                    <BrandMark variant="dark" size="lg" className="pt-2" />
                </Link>
            </div>

            <div className="my-4 flex flex-col space-y-4 rounded border bg-white p-4">
                <h3 className="text-xl font-bold">{t("auth.reset.title")}</h3>

                {/* 토큰 없이 이 주소로 직접 오는 경우 — 폼을 그려 봐야 반드시 실패한다. */}
                {!token ? (
                    <>
                        <p role="alert" className="text-sm text-red-600">
                            {t("auth.reset.no_token")}
                        </p>
                        <Link to="/forgot" className="text-sm text-blue-600 hover:underline">
                            {t("auth.reset.go_forgot")}
                        </Link>
                    </>
                ) : (
                    <form onSubmit={submit} className="flex flex-col gap-3">
                        <label htmlFor="new-password" className="text-sm font-semibold">
                            {t("auth.reset.new_password")}
                        </label>
                        <input
                            id="new-password"
                            type="password"
                            autoComplete="new-password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            className="rounded border px-3 py-2 text-sm"
                        />

                        <label htmlFor="confirm-password" className="text-sm font-semibold">
                            {t("auth.reset.confirm_password")}
                        </label>
                        <input
                            id="confirm-password"
                            type="password"
                            autoComplete="new-password"
                            value={confirm}
                            onChange={(e) => setConfirm(e.target.value)}
                            className="rounded border px-3 py-2 text-sm"
                        />

                        <button
                            type="submit"
                            disabled={sending}
                            className="mt-2 rounded border px-3 py-2 text-sm font-semibold
                                       hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {sending ? t("auth.reset.submitting") : t("auth.reset.submit")}
                        </button>

                        <p role="status" className="text-sm text-emerald-700">
                            {done}
                        </p>
                        {error && (
                            <p role="alert" className="text-sm text-red-600">
                                {error}
                            </p>
                        )}
                    </form>
                )}
            </div>
        </div>
    );
};

export default ResetPasswordPage;
