import { useState } from "react";

import { signOutEverywhere } from "util/api";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 모든 기기에서 로그아웃(user-service docs/68 §8).
 *
 * <p>"새로운 기기에서 로그인되었습니다" · "보안을 위해 로그인이 해제되었습니다" 알림을 받은 사용자가 <b>당장 할 수 있는 조치</b>다.
 * 바로 위 "기억 지우기" 는 로그아웃이 아니라서, 예전에는 비밀번호 변경 말고는 다른 기기를 끊을 방법이 없었다.
 *
 * <p>두 번 누르게 한다 — 이 기기도 로그아웃되기 때문이다. 확인 문구는 결과를 정직하게 말한다: 다른 기기는 <b>즉시가 아니라
 * 최대 15분 안에</b> 끊긴다(이미 가진 액세스 토큰은 수명까지 유효하다).
 *
 * @param onSignedOut 끊은 뒤 이 기기를 로그아웃시키는 쪽(화면 이동 · 인증 상태 정리). 이 컴포넌트는 라우터 · 인증 상태를 모른다.
 */
const SignOutEverywhere = ({ onSignedOut = () => {} }) => {
    const { t } = useTranslation();
    const [confirming, setConfirming] = useState(false);
    const [busy, setBusy] = useState(false);
    const [failed, setFailed] = useState(false);

    const run = async () => {
        if (busy) return;
        setBusy(true);
        setFailed(false);
        try {
            await signOutEverywhere();
            onSignedOut();
        } catch (e) {
            setFailed(true);
            setBusy(false);
        }
    };

    return (
        <div className="mt-6">
            <h4 className="text-sm font-semibold">{t("profile.sessions.title")}</h4>
            <p className="mt-1 text-xs text-slate-500">{t("profile.sessions.intro")}</p>

            {!confirming ? (
                <button
                    type="button"
                    onClick={() => setConfirming(true)}
                    className="mt-2 rounded-md border border-slate-300 px-4 py-2 text-sm font-semibold hover:border-red-600 hover:text-red-600"
                >
                    {t("profile.sessions.button")}
                </button>
            ) : (
                <div className="mt-2 rounded-md border border-red-200 bg-red-50 p-3">
                    <p className="text-sm">{t("profile.sessions.confirm")}</p>
                    <div className="mt-3 flex gap-2">
                        <button
                            type="button"
                            onClick={() => setConfirming(false)}
                            disabled={busy}
                            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-semibold disabled:opacity-40"
                        >
                            {t("profile.sessions.cancel")}
                        </button>
                        <button
                            type="button"
                            onClick={run}
                            disabled={busy}
                            className="rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40"
                        >
                            {t("profile.sessions.confirm_button")}
                        </button>
                    </div>
                </div>
            )}

            {failed && <p className="mt-2 text-sm text-red-600">{t("profile.sessions.failed")}</p>}
        </div>
    );
};

export default SignOutEverywhere;
