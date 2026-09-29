import { useCallback, useEffect, useRef, useState } from "react";

import {
    adminProductImportStatus,
    adminStartProductImport,
    adminStorageHealth,
} from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";
import { batchStatusLabel } from "util/statusLabel";

const formatTime = (value) => formatDateTime(value, "-");

// 사전 키만 둔다 — 모듈이 읽힐 때 번역하면 그때의 언어로 굳는다. 그릴 때 t 로 꺼낸다.
const STATE_LABEL_KEY = {
    IDLE: "admin.import.state.idle",
    RUNNING: "admin.import.state.running",
    COMPLETED: "admin.import.state.completed",
    FAILED: "admin.import.state.failed",
};

/**
 * CSV 일괄 상품 등록.
 *
 * <p>번들된 CSV(카테고리 → 서브카테고리 → 상품 → 색상 → SKU → 상세 → Q&A → 사이즈)를
 * 순서대로 넣고, 이미지도 함께 S3 로 올린다. <b>초기 데이터 투입용</b>이지 상시 등록
 * 수단이 아니다 — 이 잡은 멱등하지 않아서 두 번 돌리면 상품이 두 벌 생긴다.
 *
 * <p>예전에는 이 기능에 화면이 없었다. {@code POST /api/products/load} 를 직접 쳐야 했고,
 * 그 요청은 잡이 끝날 때까지 응답을 주지 않아 게이트웨이 타임아웃에 먼저 걸렸다.
 * 관리자는 504 를 보고 "실패했구나" 하고 다시 눌렀다. 지금은 즉시 돌아오고, 진행 상황을
 * 여기서 따로 묻는다.
 */
const ProductImport = () => {
    const { t } = useTranslation();
    const [status, setStatus] = useState(null);
    const [storage, setStorage] = useState(null);
    const [error, setError] = useState("");
    // 서버가 "카탈로그가 차 있다"로 거절했는가 — 그때만 비우기를 제안한다.
    const [needsReset, setNeedsReset] = useState(false);
    const [busy, setBusy] = useState(false);
    const timer = useRef(null);

    const refresh = useCallback(async () => {
        try {
            setStatus(await adminProductImportStatus());
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.import.load_failed"));
        }
    }, [t]);

    useEffect(() => {
        refresh();
        adminStorageHealth().then(setStorage).catch(() => setStorage(null));
    }, [refresh]);

    // 진행 중일 때만 폴링한다. 끝난 뒤에도 계속 물으면 관리자 화면 하나가 서버에
    // 초당 요청을 꽂는 셈이 된다.
    useEffect(() => {
        clearInterval(timer.current);
        if (status?.state === "RUNNING") {
            timer.current = setInterval(refresh, 3000);
        }
        return () => clearInterval(timer.current);
    }, [status?.state, refresh]);

    /**
     * 임포트 시작.
     *
     * <p>이 잡은 멱등하지 않아, 카탈로그가 비어 있지 않으면 서버가 409 로 거절한다.
     * 그때 화면이 "비우고 다시 넣기"를 제안한다 — 그 길이 없으면 운영자는 psql 로 직접
     * TRUNCATE 하게 되고, 실제로 그렇게 했다(그 자리에서 시퀀스까지 되돌려야 한다는 것을
     * 알아야 한다 — CatalogResetService 주석 참조).
     */
    const start = async (resetArg = false) => {
        if (busy) return;
        // ⚠️ `onClick={start}` 로 붙이면 클릭 **이벤트**가 첫 인자로 들어온다. 그걸
        //    그대로 쓰면 truthy 라 일반 버튼이 카탈로그를 지운다. 호출부도 고쳤지만,
        //    여기서 정확히 true 만 통과시켜 두 겹으로 막는다.
        const reset = resetArg === true;

        const question = reset
            ? t("admin.import.confirm_reset")
            : t("admin.import.confirm_start");
        if (!window.confirm(question)) {
            return;
        }

        setBusy(true);
        setError("");
        setNeedsReset(false);
        try {
            setStatus(await adminStartProductImport(reset));
        } catch (e) {
            const res = e?.response;
            setError(res?.data?.message ?? t("admin.import.start_failed"));
            // 409 중에서도 "카탈로그가 차 있다"일 때만 비우기를 제안한다. "이미 진행 중"에
            // 비우기를 권하면 돌고 있는 잡의 발밑을 지우게 된다.
            setNeedsReset(
                res?.status === 409 && String(res?.data?.message ?? "").includes("reset=true"));
            await refresh();
        } finally {
            setBusy(false);
        }
    };

    const running = status?.state === "RUNNING";
    const blocked = storage ? !storage.writable : false;

    return (
        <div className="p-6">
            <h1 className="mb-2 text-2xl font-bold">{t("admin.import.title")}</h1>
            <p className="mb-6 text-sm text-slate-600">
                {t("admin.import.intro")}
            </p>

            {error && (
                <div className="mb-4">
                    <p className="text-red-600">{error}</p>
                    {needsReset && (
                        <button
                            type="button"
                            disabled={busy || running}
                            onClick={() => start(true)}
                            className="mt-2 rounded border border-red-400 px-3 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
                        >
                            {t("admin.import.reset_and_retry")}
                        </button>
                    )}
                </div>
            )}

            {blocked && (
                <div className="mb-4 rounded border border-red-300 bg-red-50 p-4 text-sm">
                    <p className="font-semibold text-red-700">
                        {t("admin.import.blocked")}
                    </p>
                    <p className="mt-1 text-red-700">
                        {t("admin.storage.bucket")}: <code>{storage.bucket}</code>
                    </p>
                    {storage.detail && (
                        <p className="mt-1 break-all text-red-600">{storage.detail}</p>
                    )}
                </div>
            )}

            <div className="mb-6 rounded-md border border-slate-200 p-4">
                <dl className="grid grid-cols-[8rem_1fr] gap-y-2 text-sm">
                    <dt className="text-slate-500">{t("admin.import.label.status")}</dt>
                    <dd className={status?.state === "FAILED" ? "text-red-600" : ""}>
                        {STATE_LABEL_KEY[status?.state] ? t(STATE_LABEL_KEY[status.state]) : "-"}
                    </dd>
                    <dt className="text-slate-500">{t("admin.import.label.started_at")}</dt>
                    <dd>{formatTime(status?.startedAt)}</dd>
                    <dt className="text-slate-500">{t("admin.import.label.finished_at")}</dt>
                    <dd>{formatTime(status?.finishedAt)}</dd>
                    {status?.message && (
                        <>
                            <dt className="text-slate-500">{t("admin.import.label.cause")}</dt>
                            <dd className="break-all text-red-600">{status.message}</dd>
                        </>
                    )}
                </dl>

                <div className="mt-4 flex gap-2">
                    <button
                        type="button"
                        onClick={() => start(false)}
                        disabled={busy || running || blocked}
                        className="rounded bg-amazon-orange px-4 py-2 font-semibold text-amazon-blue_dark disabled:opacity-40"
                    >
                        {running ? t("admin.import.running") : t("admin.import.start")}
                    </button>
                    <button
                        type="button"
                        onClick={refresh}
                        className="rounded border border-slate-300 px-4 py-2"
                    >
                        {t("admin.import.refresh")}
                    </button>
                </div>
            </div>

            {status?.steps?.length > 0 && (
                <table className="w-full text-left text-sm">
                    <thead className="border-b border-slate-300">
                        <tr>
                            <th className="py-2">{t("admin.import.col.step")}</th>
                            <th>{t("admin.import.col.status")}</th>
                            <th>{t("admin.import.col.read")}</th>
                            <th>{t("admin.import.col.written")}</th>
                            <th>{t("admin.import.col.skipped")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {status.steps.map((step) => (
                            <tr key={step.name} className="border-b border-slate-100">
                                <td className="py-2">{step.name}</td>
                                <td className={step.status === "COMPLETED" ? "" : "text-red-600"}>
                                    {batchStatusLabel(t, step.status)}
                                </td>
                                <td>{step.read}</td>
                                <td>{step.written}</td>
                                <td>{step.skipped}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
};

export default ProductImport;
