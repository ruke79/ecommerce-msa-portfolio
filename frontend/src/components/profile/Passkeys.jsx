import { useCallback, useEffect, useState } from "react";

import { deletePasskey, getPasskeyRegistrationOptions, getPasskeys, registerPasskey } from "util/api";
import { credentialToJSON, isPasskeySupported, isUserCancel, toCreationOptions } from "util/passkey";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 패스키 — 등록 · 목록 · 삭제(user-service docs/68 §20).
 *
 * <p>등록은 서버가 "최근 로그인" 을 요구한다(403 code=reauth_required). 열어 둔 세션만으로 누군가 자기 패스키를 심으면,
 * 주인이 비밀번호를 바꿔도 남는 뒷문이 되기 때문이다. 그 경우 다시 로그인하라고 안내한다.
 *
 * <p>목록에는 이름 · 등록일 · 마지막 사용만 보인다 — 공개 키 · 자격증명 id 는 서버가 내려 주지 않는다.
 */
// [JA] パスキー — 登録 · 一覧 · 削除。登録はサーバーが「最近のログイン」を要求する(盗んだセッションで裏口を作らせない)。
const Passkeys = () => {
    const { t } = useTranslation();
    const [passkeys, setPasskeys] = useState([]);
    const [name, setName] = useState("");
    const [busy, setBusy] = useState(false);
    // 문구가 아니라 문구 키를 담는다 — 그릴 때 지금 언어로 바꾼다.
    const [messageKey, setMessageKey] = useState("");
    const [error, setError] = useState(false);
    const supported = isPasskeySupported();

    const load = useCallback(async () => {
        try {
            setPasskeys(await getPasskeys());
        } catch (e) {
            setError(true);
            setMessageKey("profile.passkeys.load_failed");
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const add = async () => {
        if (busy) return;
        setBusy(true);
        setMessageKey("");
        setError(false);
        try {
            const options = await getPasskeyRegistrationOptions();
            const credential = await navigator.credentials.create({ publicKey: toCreationOptions(options) });
            await registerPasskey(name.trim(), credentialToJSON(credential));
            setName("");
            setMessageKey("profile.passkeys.added");
            await load();
        } catch (e) {
            if (isUserCancel(e)) return;
            setError(true);
            if (e?.response?.data?.code === "reauth_required") {
                setMessageKey("profile.passkeys.reauth");
            } else if (e?.name === "InvalidStateError") {
                // excludeCredentials 에 걸렸다 — 이 기기에는 이미 이 계정의 패스키가 있다.
                setMessageKey("profile.passkeys.already");
            } else {
                setMessageKey("profile.passkeys.add_failed");
            }
        } finally {
            setBusy(false);
        }
    };

    const remove = async (passkeyId) => {
        if (busy) return;
        setBusy(true);
        setMessageKey("");
        try {
            await deletePasskey(passkeyId);
            await load();
        } catch (e) {
            setError(true);
            setMessageKey("profile.passkeys.delete_failed");
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="mt-10 border-t pt-6">
            <h3 className="font-semibold">{t("profile.passkeys.title")}</h3>
            <p className="mt-1 text-xs text-slate-500">{t("profile.passkeys.intro")}</p>

            {!supported && <p className="mt-3 text-sm text-slate-500">{t("profile.passkeys.unsupported")}</p>}

            {supported && (
                <div className="mt-3 flex gap-2">
                    <input
                        type="text"
                        value={name}
                        maxLength={60}
                        onChange={(e) => setName(e.target.value)}
                        placeholder={t("profile.passkeys.name_placeholder")}
                        aria-label={t("profile.passkeys.name_placeholder")}
                        className="flex-1 border rounded px-2 py-1 text-sm"
                    />
                    <button
                        type="button"
                        onClick={add}
                        disabled={busy}
                        className="text-sm px-3 py-1 rounded bg-amazon-orange text-white hover:opacity-90 disabled:opacity-40"
                    >
                        {t("profile.passkeys.add")}
                    </button>
                </div>
            )}

            {messageKey && (
                <p className={`mt-2 text-sm ${error ? "text-red-600" : "text-green-600"}`} role={error ? "alert" : "status"}>
                    {t(messageKey)}
                </p>
            )}

            {passkeys.length === 0 && !error && (
                <p className="mt-3 text-sm text-slate-500">{t("profile.passkeys.empty")}</p>
            )}

            <ul className="mt-3 divide-y divide-slate-100">
                {passkeys.map((passkey) => (
                    <li key={passkey.id} className="flex items-center gap-3 py-2">
                        <div className="flex-1">
                            <p className="text-sm font-medium">
                                {passkey.name}
                                {passkey.synced && (
                                    <span className="ml-2 text-xs text-slate-400">{t("profile.passkeys.synced")}</span>
                                )}
                            </p>
                            <p className="text-xs text-slate-500">
                                {t("profile.passkeys.created", { at: formatDateTime(passkey.createdAt) })}
                                {" · "}
                                {passkey.lastUsedAt
                                    ? t("profile.passkeys.last_used", { at: formatDateTime(passkey.lastUsedAt) })
                                    : t("profile.passkeys.never_used")}
                            </p>
                        </div>
                        <button
                            type="button"
                            onClick={() => remove(passkey.id)}
                            disabled={busy}
                            className="text-xs text-slate-500 underline disabled:opacity-40"
                        >
                            {t("profile.passkeys.delete")}
                        </button>
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default Passkeys;
