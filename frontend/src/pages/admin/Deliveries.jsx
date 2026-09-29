import { useCallback, useEffect, useState } from "react";

import { adminCreateDeliveryForOrder, adminListDeliveries, fetchCarriers, registerTracking } from "api/deliveryApi";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";
import { countryName } from "util/country";
import { deliveryStatusLabel } from "util/statusLabel";

/**
 * 관리자 배송 — 송장을 기다리는 배송에 택배사와 송장번호를 붙인다.
 *
 * <p>결제가 끝나면 delivery-service 가 배송 건을 만들고(PENDING) 주문은 PROCESSING 이 된다.
 * 그 다음은 사람이 한다: 물건을 포장해 택배사에 넘기고 <b>여기서 송장을 등록</b>한다.
 * 그 순간부터 서버가 택배사를 폴링하고 주문은 DISPATCHED → DELIVERED 로 간다.
 * 이 화면이 없으면 주문은 영원히 "처리 중" 에 머문다.
 *
 * <p>등록 뒤에는 목록을 <b>서버에서 다시 읽는다.</b> 환불 화면과 같은 이유다 — 서버가
 * 거절했는데 화면만 바뀌는 경우를 만들지 않는다.
 *
 * <p>시뮬레이터 택배사(SIMULATED)는 송장번호가 SIM 으로 시작하면 정해진 각본대로 진행한다
 * (SIMEXC 예외, SIMFAIL 배달 실패 뒤 성공, SIMINTL 통관). 실제 택배사가 없는 환경에서
 * 전 경로를 돌려 보는 용도다.
 */
const formatDate = (value) => formatDateTime(value, "-");

// 배송의 나라는 alpha-2 코드다 — 화면 언어의 이름으로.
const addressLine = (a, lang) => {
    if (!a) return "-";
    const bits = [a.postalCode ? `[${a.postalCode}]` : null, a.line1, a.line2, a.city, a.state, a.country ? countryName(a.country, lang) : null];
    return bits.filter(Boolean).join(" ");
};

const Deliveries = () => {
    const { t, lang } = useTranslation();
    const [awaiting, setAwaiting] = useState(true);
    const [rows, setRows] = useState([]);
    const [carriers, setCarriers] = useState([]);
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [editing, setEditing] = useState(null);        // orderId — 송장 입력 중
    const [carrierCode, setCarrierCode] = useState("");
    const [trackingNumber, setTrackingNumber] = useState("");
    const [backfillId, setBackfillId] = useState("");    // 배송이 없는 주문의 주문 번호
    const [notice, setNotice] = useState("");

    const load = useCallback(async () => {
        setError("");
        try {
            setRows(await adminListDeliveries({ awaiting }));
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.deliveries.error.load"));
        }
    }, [awaiting, t]);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        (async () => {
            try {
                const list = await fetchCarriers();
                setCarriers(list);
                if (list.length > 0) setCarrierCode((c) => c || list[0].code);
            } catch {
                setCarriers([]);
            }
        })();
    }, []);

    const openEditor = (row) => {
        setEditing(row.delivery.orderId);
        setTrackingNumber("");
    };

    const submit = async (row) => {
        if (busy) return;
        const number = trackingNumber.trim();
        // 테스트 택배사는 번호를 비워도 된다 — 서버가 SIM-<주문>-<상자> 를 만든다.
        if (!carrierCode || (!number && carrierCode !== "SIMULATED")) {
            setError(t("admin.deliveries.error.need_tracking"));
            return;
        }
        setBusy(true);
        setError("");
        try {
            await registerTracking(row.delivery.orderId, { carrierCode, trackingNumber: number });
            setEditing(null);
            setTrackingNumber("");
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.deliveries.error.register"));
        } finally {
            setBusy(false);
        }
    };

    /**
     * 결제는 됐는데 배송이 없는 주문을 손으로 이어 준다.
     *
     * 정상 경로는 결제 이벤트가 만든다. 그 경로가 막혀 있던 동안 결제된 주문들이 배송 없이
     * 남았는데(docs/25 §11.5), 그 주문은 이 목록에 **아예 나타나지 않는다** — 배송이 없으니까.
     * 고칠 화면이 없으면 영영 못 고친다. 그래서 주문 번호로 직접 만든다.
     */
    const backfill = async () => {
        const id = backfillId.trim();
        if (busy || !id) {
            setError(t("admin.deliveries.error.need_order_id"));
            return;
        }
        setBusy(true);
        setError("");
        setNotice("");
        try {
            const result = await adminCreateDeliveryForOrder(id);
            setNotice(result?.created
                ? t("admin.deliveries.created", { id })
                : (result?.message ?? t("admin.deliveries.error.create")));
            setBackfillId("");
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.deliveries.error.create"));
        } finally {
            setBusy(false);
        }
    };

    /** 송장 없는 상자가 하나라도 있으면 등록 대상이다. 취소된 배송은 아니다. */
    const canRegister = (row) => row.delivery.status !== "CANCELLED"
        && (row.delivery.shipments ?? []).some((s) => !s.trackingNumber && s.status === "PENDING");

    const itemLines = (row) => (row.delivery.shipments ?? []).flatMap((s) => s.items ?? [])
        // 창고는 이름이 아니라 SKU 로 집는다 — 포장 목록에 코드를 함께(catalog V17 → 주문 품목 → 배송)
        .map((i) => `${i.productName ?? "-"}${i.skuCode ? ` [${i.skuCode}]` : ""} ×${i.quantity}`);

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.deliveries.title")}</h1>

            {error && <p className="mb-4 text-red-600" role="alert">{error}</p>}
            {notice && <p className="mb-4 text-emerald-700" role="status">{notice}</p>}

            {/* 결제는 됐는데 배송이 없는 주문 — 목록에 뜨지 않으므로 번호로 찾아 만든다. */}
            <div className="mb-4 flex flex-wrap items-end gap-3 rounded border border-slate-200 bg-slate-50 p-3">
                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.deliveries.backfill.label")}</span>
                    <input
                        aria-label={t("admin.deliveries.backfill.order_id")}
                        value={backfillId}
                        onChange={(e) => setBackfillId(e.target.value)}
                        placeholder={t("admin.deliveries.backfill.order_id")}
                        className="mt-1 block w-72 rounded border border-slate-300 p-2 font-mono"
                    />
                </label>
                <button
                    type="button"
                    disabled={busy}
                    onClick={backfill}
                    className="rounded border border-slate-600 px-3 py-2 text-slate-700 hover:bg-slate-600 hover:text-white disabled:opacity-40"
                >
                    {t("admin.deliveries.backfill.submit")}
                </button>
                <span className="pb-2 text-xs text-slate-500">
                    {t("admin.deliveries.backfill.note")}
                </span>
            </div>

            <div className="mb-4 flex flex-wrap items-end gap-3">
                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.deliveries.view")}</span>
                    <select
                        aria-label={t("admin.deliveries.view")}
                        value={awaiting ? "awaiting" : "all"}
                        onChange={(e) => setAwaiting(e.target.value === "awaiting")}
                        className="mt-1 block rounded border border-slate-300 p-2"
                    >
                        <option value="awaiting">{t("admin.deliveries.view.awaiting")}</option>
                        <option value="all">{t("admin.deliveries.view.all")}</option>
                    </select>
                </label>
                <span className="pb-2 text-sm text-slate-500">{t("admin.deliveries.total", { count: rows.length })}</span>
            </div>

            <table className="w-full border-collapse text-sm">
                <thead>
                    <tr className="border-b bg-slate-50 text-left">
                        <th className="p-2">{t("admin.deliveries.col.order")}</th>
                        <th className="p-2">{t("admin.deliveries.col.recipient")}</th>
                        <th className="p-2">{t("admin.deliveries.col.address")}</th>
                        <th className="p-2">{t("admin.deliveries.col.items")}</th>
                        <th className="p-2">{t("admin.deliveries.col.status")}</th>
                        <th className="p-2">{t("admin.deliveries.col.created")}</th>
                        <th className="p-2">{t("admin.deliveries.col.tracking")}</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={7} className="p-6 text-center text-slate-500">
                            {awaiting ? t("admin.deliveries.empty.awaiting") : t("admin.deliveries.empty.all")}
                        </td></tr>
                    )}
                    {rows.map((row) => {
                        const d = row.delivery;
                        return (
                            <tr key={d.deliveryId} className="border-b align-top">
                                <td className="p-2">
                                    <div className="font-mono">#{d.orderId}</div>
                                    <div className="text-xs text-slate-500">{t("admin.deliveries.customer", { id: row.customerId })}</div>
                                </td>
                                <td className="p-2">
                                    <div>{d.address?.recipientName ?? "-"}</div>
                                    <div className="text-xs text-slate-500">{d.address?.recipientPhone ?? ""}</div>
                                </td>
                                <td className="p-2">
                                    <div>{addressLine(d.address, lang)}</div>
                                    {d.address?.memo && <div className="text-xs text-slate-500">{t("admin.deliveries.memo", { memo: d.address.memo })}</div>}
                                </td>
                                <td className="p-2">
                                    {itemLines(row).map((line, i) => <div key={i} className="text-xs">{line}</div>)}
                                </td>
                                <td className="p-2">
                                    <div>{deliveryStatusLabel(t, d.status)}</div>
                                    {(d.shipments ?? []).filter((s) => s.trackingNumber).map((s) => (
                                        <div key={s.shipmentId} className="text-xs text-slate-500">
                                            {s.carrierName ?? s.carrierCode} · {s.trackingNumber}
                                        </div>
                                    ))}
                                </td>
                                <td className="p-2">{formatDate(row.createdAt)}</td>
                                <td className="p-2">
                                    {canRegister(row) && editing !== d.orderId && (
                                        <button
                                            type="button"
                                            disabled={busy}
                                            onClick={() => openEditor(row)}
                                            className="rounded border border-blue-600 px-3 py-1 text-blue-700 hover:bg-blue-600 hover:text-white disabled:opacity-40"
                                        >
                                            {t("admin.deliveries.register_tracking")}
                                        </button>
                                    )}
                                    {canRegister(row) && editing === d.orderId && (
                                        <div className="w-64 rounded border border-blue-300 bg-blue-50 p-2">
                                            <label className="block text-xs text-slate-700">
                                                {t("admin.deliveries.carrier")}
                                                <select
                                                    aria-label={t("admin.deliveries.carrier")}
                                                    value={carrierCode}
                                                    onChange={(e) => setCarrierCode(e.target.value)}
                                                    className="mt-1 block w-full rounded border border-slate-300 p-1 text-xs"
                                                >
                                                    {carriers.map((c) => (
                                                        <option key={c.code} value={c.code}>{c.name}</option>
                                                    ))}
                                                </select>
                                            </label>
                                            <label className="mt-1 block text-xs text-slate-700">
                                                {t("admin.deliveries.tracking_number")}
                                                <input
                                                    aria-label={t("admin.deliveries.tracking_number")}
                                                    value={trackingNumber}
                                                    onChange={(e) => setTrackingNumber(e.target.value)}
                                                    maxLength={60}
                                                    placeholder={carrierCode === "SIMULATED"
                                                        ? t("admin.deliveries.tracking_placeholder_simulated")
                                                        : t("admin.deliveries.tracking_number")}
                                                    className="mt-1 block w-full rounded border border-slate-300 p-1 text-xs"
                                                />
                                            </label>
                                            <div className="mt-2 flex gap-2">
                                                <button
                                                    type="button"
                                                    disabled={busy}
                                                    onClick={() => submit(row)}
                                                    className="flex-1 rounded bg-blue-600 px-2 py-1 text-xs text-white disabled:opacity-40"
                                                >
                                                    {busy ? t("admin.deliveries.registering") : t("admin.deliveries.register")}
                                                </button>
                                                <button
                                                    type="button"
                                                    disabled={busy}
                                                    onClick={() => setEditing(null)}
                                                    className="flex-1 rounded border px-2 py-1 text-xs"
                                                >
                                                    {t("common.cancel")}
                                                </button>
                                            </div>
                                        </div>
                                    )}
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
};

export default Deliveries;
