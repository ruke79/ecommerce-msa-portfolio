import { useCallback, useEffect, useState } from "react";

import { forgetDevice, getMyDevices } from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 로그인한 기기 목록.
 *
 * <p>"새로운 기기에서 로그인되었습니다" 알림을 받은 사용자가 <b>갈 곳</b>이다. 알림만
 * 있고 확인할 화면이 없으면 사용자가 할 수 있는 일은 비밀번호 변경뿐이고, 그마저도
 * 정말 필요한 상황인지 판단할 근거가 없다.
 *
 * <p><b>⚠️ "기억 지우기" 는 로그아웃이 아니다.</b> 그 기기에 로그인된 상태가 있으면 그대로
 * 남는다 — 뜻은 "다음에 그 기기에서 로그인하면 새 기기로 보고 다시 알린다" 뿐이다.
 * 문구가 "로그아웃시킨다" 로 읽히면 사용자는 조치를 했다고 믿고 더 이상 아무것도 하지
 * 않는다. 정말 필요한 것은 비밀번호 변경이고, 화면이 그렇게 말해야 한다.
 *
 * <p>IP 는 서버에서 이미 마스킹돼 온다. 전체 주소는 저장되지도 내려오지도 않는다.
 */
const LoginDevices = () => {
    const { t } = useTranslation();
    const [devices, setDevices] = useState([]);
    const [loading, setLoading] = useState(true);
    // 문구가 아니라 문구 키를 담는다 — 그릴 때 지금 언어로 바꾼다.
    const [errorKey, setErrorKey] = useState("");
    const [busy, setBusy] = useState(false);

    const load = useCallback(async () => {
        setErrorKey("");
        try {
            setDevices(await getMyDevices());
        } catch (e) {
            setErrorKey("profile.devices.load_failed");
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        load();
    }, [load]);

    const forget = async (deviceId) => {
        if (busy) return;
        setBusy(true);
        try {
            await forgetDevice(deviceId);
            await load();
        } catch (e) {
            setErrorKey("profile.devices.forget_failed");
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="mt-10 border-t pt-6">
            <h3 className="font-semibold">{t("profile.devices.title")}</h3>
            <p className="mt-1 text-xs text-slate-500">
                {t("profile.devices.intro")}
                <b>{t("profile.devices.intro_bold")}</b>
            </p>

            {loading && <p className="mt-3 text-sm text-slate-500">{t("profile.loading")}</p>}
            {errorKey && <p className="mt-3 text-sm text-red-600">{t(errorKey)}</p>}

            {!loading && !errorKey && devices.length === 0 && (
                <p className="mt-3 text-sm text-slate-500">{t("profile.devices.empty")}</p>
            )}

            <ul className="mt-3 divide-y divide-slate-100">
                {devices.map((device) => (
                    <li key={device.id} className="flex items-center gap-3 py-2">
                        <div className="flex-1">
                            <p className="text-sm font-medium">{device.label}</p>
                            <p className="text-xs text-slate-500">
                                {device.lastIp && `${device.lastIp} · `}
                                {t("profile.devices.last_login", { at: formatDateTime(device.lastSeenAt) })}
                            </p>
                        </div>
                        <button
                            type="button"
                            onClick={() => forget(device.id)}
                            disabled={busy}
                            className="text-xs text-slate-500 underline disabled:opacity-40"
                        >
                            {t("profile.devices.forget")}
                        </button>
                    </li>
                ))}
            </ul>

            {devices.length > 0 && (
                <p className="mt-2 text-xs text-slate-400">
                    {t("profile.devices.note_before")}
                    <b>{t("profile.devices.note_bold")}</b>
                    {t("profile.devices.note_after")}
                </p>
            )}
        </div>
    );
};

export default LoginDevices;
