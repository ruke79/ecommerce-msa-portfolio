import { useCallback, useEffect, useState } from "react";

import {
    cancelWithdrawal,
    getWithdrawalStatus,
    requestWithdrawal,
} from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/** 유예 기간(일). 서버의 탈퇴 유예 기간과 같다. */
const GRACE_DAYS = 7;

/**
 * 회원 탈퇴 — 신청과 취소.
 *
 * <p>되돌릴 수 없는 동작이라 한 번에 눌리지 않게 세 겹으로 막는다: 펼치기 → 비밀번호 →
 * 확인 체크박스. 그러고도 일주일의 유예 기간이 남아 있고, 이 화면에서 바로 취소할 수 있다.
 *
 * <p>유예 기간 동안 계정은 평소처럼 쓸 수 있다. 잠그면 취소할 방법이 사라지기 때문이다 —
 * 그래서 이 패널이 "언제 지워지는지"를 계속 보여 주는 역할을 한다.
 */
const AccountWithdrawal = () => {
    const { t } = useTranslation();
    const [status, setStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [open, setOpen] = useState(false);
    const [password, setPassword] = useState("");
    const [reason, setReason] = useState("");
    const [confirmed, setConfirmed] = useState(false);
    const [error, setError] = useState("");

    const load = useCallback(async () => {
        try {
            setStatus(await getWithdrawalStatus());
        } catch (e) {
            // 상태를 못 읽는다고 보안 설정 화면 전체가 죽으면 안 된다. 패널만 접는다.
            setStatus(null);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const reset = () => {
        setPassword("");
        setReason("");
        setConfirmed(false);
        setOpen(false);
        setError("");
    };

    const handleRequest = async () => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            setStatus(await requestWithdrawal({ password, reason }));
            reset();
        } catch (e) {
            setError(
                e?.response?.data?.message ?? t("profile.withdrawal.request_failed")
            );
        } finally {
            setBusy(false);
        }
    };

    const handleCancel = async () => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            setStatus(await cancelWithdrawal());
        } catch (e) {
            setError(e?.response?.data?.message ?? t("profile.withdrawal.cancel_failed"));
        } finally {
            setBusy(false);
        }
    };

    if (loading || !status) {
        return null;
    }

    if (status.pending) {
        // 서버가 보낸 예정 시각을 그대로 믿지 않는다 — 형식이 어긋나면 날짜 대신 남은
        // 일수만 보여 준다. 남은 일수는 서버가 계산한 값이라 항상 맞다.
        const scheduled = formatDateTime(status.scheduledAt, null);

        return (
            <div className="mt-10 border-t pt-6">
                <h3 className="font-semibold text-lg text-red-600">{t("profile.withdrawal.pending_title")}</h3>
                <div className="mt-2 rounded-md border border-red-200 bg-red-50 p-4 text-sm">
                    <p>
                        {t("profile.withdrawal.days_before")}
                        <b>{t("profile.withdrawal.days_bold", { days: status.daysLeft })}</b>
                        {t("profile.withdrawal.days_after")}
                        {scheduled && <span className="text-slate-600"> ({scheduled})</span>}
                    </p>
                    <p className="mt-2 text-slate-600">
                        {t("profile.withdrawal.pending_detail")}
                    </p>
                    <button
                        type="button"
                        onClick={handleCancel}
                        disabled={busy}
                        className={`mt-4 w-full rounded-md p-3 font-semibold text-white ${
                            busy ? "bg-slate-400 cursor-not-allowed" : "bg-slate-700 hover:bg-slate-800"
                        }`}
                    >
                        {busy ? t("profile.withdrawal.processing") : t("profile.withdrawal.cancel_request")}
                    </button>
                    {error && <p className="mt-2 text-red-600">{error}</p>}
                </div>
            </div>
        );
    }

    return (
        <div className="mt-10 border-t pt-6">
            <h3 className="font-semibold text-lg">{t("profile.withdrawal.title")}</h3>

            {!open ? (
                <button
                    type="button"
                    onClick={() => setOpen(true)}
                    className="mt-2 text-sm text-slate-500 underline hover:text-red-600"
                >
                    {t("profile.withdrawal.open")}
                </button>
            ) : (
                <div className="mt-3 rounded-md border border-slate-200 p-4 text-sm">
                    <p className="text-slate-700">
                        {t("profile.withdrawal.notice_before")}
                        <b>{t("profile.withdrawal.notice_bold", { days: GRACE_DAYS })}</b>
                        {t("profile.withdrawal.notice_after")}
                    </p>
                    <p className="mt-2 text-slate-600">
                        {t("profile.withdrawal.data_notice")}
                    </p>

                    <label className="mt-4 block">
                        <span className="text-slate-700">{t("common.password")}</span>
                        <input
                            type="password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            autoComplete="current-password"
                            className="mt-1 w-full rounded-md border border-slate-300 p-2 outline-none"
                        />
                        <span className="mt-1 block text-xs text-slate-500">
                            {t("profile.withdrawal.social_hint")}
                        </span>
                    </label>

                    <label className="mt-3 block">
                        <span className="text-slate-700">{t("profile.withdrawal.reason")}</span>
                        <input
                            type="text"
                            value={reason}
                            maxLength={255}
                            onChange={(e) => setReason(e.target.value)}
                            className="mt-1 w-full rounded-md border border-slate-300 p-2 outline-none"
                        />
                    </label>

                    <label className="mt-4 flex items-start gap-2">
                        <input
                            type="checkbox"
                            checked={confirmed}
                            onChange={(e) => setConfirmed(e.target.checked)}
                            className="mt-1"
                        />
                        <span className="text-slate-700">
                            {t("profile.withdrawal.confirm", { days: GRACE_DAYS })}
                        </span>
                    </label>

                    {error && <p className="mt-3 text-red-600">{error}</p>}

                    <div className="mt-4 flex gap-2">
                        <button
                            type="button"
                            onClick={reset}
                            disabled={busy}
                            className="w-full rounded-md border border-slate-300 p-3 font-semibold"
                        >
                            {t("common.cancel")}
                        </button>
                        <button
                            type="button"
                            onClick={handleRequest}
                            disabled={busy || !confirmed}
                            className={`w-full rounded-md p-3 font-semibold text-white ${
                                busy || !confirmed
                                    ? "bg-slate-400 cursor-not-allowed"
                                    : "bg-red-600 hover:bg-red-700"
                            }`}
                        >
                            {busy ? t("profile.withdrawal.processing") : t("profile.withdrawal.submit")}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};

export default AccountWithdrawal;
