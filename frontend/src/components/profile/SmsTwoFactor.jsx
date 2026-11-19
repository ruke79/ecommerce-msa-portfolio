import { useCallback, useEffect, useState } from "react";

import api from "util/api";
import { useTranslation } from "i18n/I18nProvider";

const BASE = "/user-service/api/auth";

/**
 * SMS 2단계 인증 — 켜기 · 끄기(user-service docs/24 §10).
 *
 * <p>켜기: 서버가 "최근 로그인"(10분)을 요구하고, 지금 등록된 번호로 보낸 6자리가 맞아야 켜진다. 켤 때의 번호에 묶인다 — 그 뒤
 * 번호를 바꾸면 SMS 수단은 멈춘다(세션만 가진 사람이 번호를 바꿔 로그인 코드를 받지 못하게).
 * 끄기: 켤 때의 번호로 받은 지금 코드가 맞아야 한다.
 *
 * <p>번호 자체는 이 화면이 다루지 않는다 — 아래의 휴대폰 인증(SmsVerification)이 등록한다.
 */
// [JA] SMS 二段階認証 — 有効化 · 無効化。有効化は直近ログインと今の登録番号へ送った 6 桁が必要で、その番号に結束する。
const SmsTwoFactor = () => {
    const { t } = useTranslation();
    const [enabled, setEnabled] = useState(null);
    // 코드를 기다리는 동작 — "enable" · "disable" · ""(대기 없음)
    const [pending, setPending] = useState("");
    const [sentTo, setSentTo] = useState("");
    const [code, setCode] = useState("");
    const [busy, setBusy] = useState(false);
    // 문구가 아니라 문구 키를 담는다 — 그릴 때 지금 언어로 바꾼다.
    const [messageKey, setMessageKey] = useState("");
    const [error, setError] = useState(false);

    const load = useCallback(async () => {
        try {
            const { data } = await api.get(`${BASE}/user/2fa-status`);
            setEnabled(Boolean(data?.smsEnabled));
        } catch (e) {
            setEnabled(null);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const fail = (e) => {
        const status = e?.response?.status;
        const body = e?.response?.data;
        const code = typeof body === "string" ? body : body?.code;
        setError(true);
        if (status === 403) {
            setMessageKey("profile.security.sms2fa.reauth");
        } else if (code === "no_phone") {
            setMessageKey("profile.security.sms2fa.no_phone");
        } else if (code === "phone_changed") {
            setMessageKey("profile.security.sms2fa.phone_changed");
        } else if (status === 401) {
            setMessageKey("profile.security.sms2fa.invalid");
        } else if (status === 429) {
            setMessageKey("profile.security.sms2fa.too_many");
        } else {
            setMessageKey("profile.security.sms2fa.failed");
        }
    };

    const start = async (action) => {
        if (busy) return;
        setBusy(true);
        setMessageKey("");
        setError(false);
        try {
            const { data } = await api.post(`${BASE}/sms-2fa/code`);
            setSentTo(data?.maskedPhone ?? "");
            setCode("");
            setPending(action);
        } catch (e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    };

    const confirm = async () => {
        if (busy || code.length !== 6) return;
        setBusy(true);
        setMessageKey("");
        setError(false);
        try {
            await api.post(`${BASE}/sms-2fa/${pending}?code=${encodeURIComponent(code)}`);
            setMessageKey(pending === "enable" ? "profile.security.sms2fa.enabled" : "profile.security.sms2fa.disabled");
            setPending("");
            setCode("");
            await load();
        } catch (e) {
            fail(e);
            setCode("");
        } finally {
            setBusy(false);
        }
    };

    if (enabled === null && !messageKey) {
        // 상태를 모르면 버튼을 보여 주지 않는다 — 켜진 계정에 '켜기' 를 띄우지 않게.
        return null;
    }

    return (
        <div className="mt-10 border-t pt-6">
            <div className="flex items-center justify-between">
                <h3 className="font-semibold">{t("profile.security.sms2fa.title")}</h3>
                <span className="text-sm text-slate-600">
                    {enabled ? t("profile.security.sms2fa.on") : t("profile.security.sms2fa.off")}
                </span>
            </div>
            <p className="mt-1 text-xs text-slate-500">{t("profile.security.sms2fa.intro")}</p>

            {!pending && enabled !== null && (
                <button
                    type="button"
                    onClick={() => start(enabled ? "disable" : "enable")}
                    disabled={busy}
                    className="mt-3 text-sm px-3 py-1 rounded bg-amazon-orange text-white hover:opacity-90 disabled:opacity-40"
                >
                    {enabled ? t("profile.security.sms2fa.disable") : t("profile.security.sms2fa.enable")}
                </button>
            )}

            {pending && (
                <div className="mt-3 flex flex-col gap-2">
                    <p className="text-sm text-slate-600">{t("profile.security.sms2fa.sent", { phone: sentTo })}</p>
                    <div className="flex gap-2">
                        <input
                            type="text"
                            inputMode="numeric"
                            autoComplete="one-time-code"
                            maxLength={6}
                            value={code}
                            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                            aria-label={t("profile.security.sms2fa.code_label")}
                            className="w-32 border rounded px-2 py-1 text-center tracking-widest"
                        />
                        <button
                            type="button"
                            onClick={confirm}
                            disabled={busy || code.length !== 6}
                            className="text-sm px-3 py-1 rounded bg-amazon-orange text-white hover:opacity-90 disabled:opacity-40"
                        >
                            {t("profile.security.sms2fa.confirm")}
                        </button>
                        <button
                            type="button"
                            onClick={() => {
                                setPending("");
                                setCode("");
                            }}
                            className="text-sm text-slate-500 hover:underline"
                        >
                            {t("profile.security.sms2fa.cancel")}
                        </button>
                    </div>
                </div>
            )}

            {messageKey && (
                <p className={`mt-2 text-sm ${error ? "text-red-600" : "text-green-600"}`} role={error ? "alert" : "status"}>
                    {t(messageKey)}
                </p>
            )}
        </div>
    );
};

export default SmsTwoFactor;
