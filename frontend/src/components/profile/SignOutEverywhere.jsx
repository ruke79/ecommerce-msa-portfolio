import { useState } from "react";

import { signOutEverywhere } from "util/api";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 모든 기기에서 로그아웃(user-service docs/68 §8).
 *
 * <p>"새로운 기기에서 로그인되었습니다" · "보안을 위해 로그인이 해제되었습니다" 알림을 받은 사용자가 <b>당장 할 수 있는 조치</b>다.
 * 바로 위 "기억 지우기" 는 로그아웃이 아니라서, 예전에는 비밀번호 변경 말고는 다른 기기를 끊을 방법이 없었다.
 *
 * <p>두 번 누르게 한다 — 이 기기도 로그아웃되기 때문이다. 확인 문구는 결과를 정직하게 말한다: 다른 기기도 <b>곧바로</b> 끊긴다
 * — 게이트웨이가 로그인 해제 시각보다 먼저 발급된 액세스 토큰을 거절한다(docs/57 §22, 2026-11-19 — 그 전에는 "최대 15분 안에" 였다:
 * 이미 가진 액세스 토큰이 수명까지 유효했다). Redis 장애 때만 예전처럼 만료까지다.
 *
 * @param onSignedOut 끊은 뒤 이 기기를 로그아웃시키는 쪽(화면 이동 · 인증 상태 정리). 이 컴포넌트는 라우터 · 인증 상태를 모른다.
 *
 * <p>[JA] 全端末からログアウト(user-service docs/68 §8)。
 * 「新しい端末でログインされました」・「セキュリティのためログインが解除されました」通知を受けたユーザーが <b>今すぐ取れる対処</b>だ。
 * すぐ上の「記憶を消す」はログアウトではないので、以前はパスワード変更以外に他の端末を切る方法がなかった。
 * 二度押させる — この端末もログアウトされるからだ。確認文言は結果を正直に言う: 他の端末も <b>すぐに</b> 切れる
 * — ゲートウェイがログイン解除時刻より前に発行されたアクセストークンを拒否する(docs/57 §22、2026-11-19 — それ以前は「最大 15 分以内に」だった:
 * 既に持っているアクセストークンが寿命まで有効だった)。Redis 障害時だけ以前のように期限切れまでだ。
 * onSignedOut: 切断後にこの端末をログアウトさせる側(画面遷移・認証状態の整理)。このコンポーネントはルーター・認証状態を知らない。
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
