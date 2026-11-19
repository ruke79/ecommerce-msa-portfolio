import { useCallback, useEffect, useState } from "react";

import api from "util/api";
import { useTranslation } from "i18n/I18nProvider";

const BASE = "/user-service/api/auth";

/**
 * 2단계 인증 복구 코드(user-service docs/57 §22) — 인증 앱 · 휴대폰을 잃었을 때 로그인 2단계를 대신하는 1회용 코드 10개.
 *
 * <p>2단계 인증(인증 앱 · SMS)이 하나라도 켜져 있을 때만 보인다. 만들면 옛 코드는 모두 무효가 되고, 새 코드는 <b>이 화면에서 한 번만</b>
 * 보인다(서버에는 해시뿐). 서버가 "최근 로그인"(10분)을 요구한다 — 훔친 세션으로 코드를 받아 가 2단계 인증을 건너뛰지 못하게.
 */
// [JA] 二段階認証のリカバリーコード — 認証アプリ · 携帯を失ったときの使い捨てコード 10 個。作成すると古いコードは無効、
//      新しいコードはこの画面で一度だけ表示。直近のログインが必要。
const RecoveryCodes = () => {
    const { t } = useTranslation();
    const [status, setStatus] = useState(null);
    const [codes, setCodes] = useState([]);
    const [busy, setBusy] = useState(false);
    const [messageKey, setMessageKey] = useState("");

    const load = useCallback(async () => {
        try {
            const { data } = await api.get(`${BASE}/user/2fa-status`);
            setStatus({
                on: Boolean(data?.is2faEnabled || data?.smsEnabled),
                remaining: Number(data?.recoveryCodesRemaining ?? 0),
            });
        } catch (e) {
            setStatus(null);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const create = async () => {
        if (busy) return;
        setBusy(true);
        setMessageKey("");
        try {
            const { data } = await api.post(`${BASE}/2fa/recovery-codes`);
            setCodes(Array.isArray(data?.codes) ? data.codes : []);
            await load();
        } catch (e) {
            setMessageKey(e?.response?.status === 403
                ? "profile.security.recovery.reauth"
                : "profile.security.recovery.failed");
        } finally {
            setBusy(false);
        }
    };

    if (!status?.on) {
        // 2단계 인증이 꺼져 있으면 복구할 것이 없다 — 상태를 모를 때도 그리지 않는다.
        return null;
    }

    return (
        <div className="mt-10 border-t pt-6">
            <div className="flex items-center justify-between">
                <h3 className="font-semibold">{t("profile.security.recovery.title")}</h3>
                <span className="text-sm text-slate-600">
                    {t("profile.security.recovery.remaining", { count: status.remaining })}
                </span>
            </div>
            <p className="mt-1 text-xs text-slate-500">{t("profile.security.recovery.intro")}</p>

            {codes.length > 0 && (
                <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-3">
                    <p className="text-sm font-semibold">{t("profile.security.recovery.save_now")}</p>
                    <ul className="mt-2 grid grid-cols-2 gap-1 font-mono text-sm" aria-label={t("profile.security.recovery.title")}>
                        {codes.map((c) => (
                            <li key={c}>{c}</li>
                        ))}
                    </ul>
                </div>
            )}

            <button
                type="button"
                onClick={create}
                disabled={busy}
                className="mt-3 text-sm px-3 py-1 rounded bg-amazon-orange text-white hover:opacity-90 disabled:opacity-40"
            >
                {status.remaining > 0 ? t("profile.security.recovery.regenerate") : t("profile.security.recovery.create")}
            </button>
            {messageKey && <p className="mt-2 text-sm text-red-600">{t(messageKey)}</p>}
        </div>
    );
};

export default RecoveryCodes;
