import { useCallback, useEffect, useState } from "react";

import { adminConfirmDeposit, adminListOrders, adminReceiveReturn, adminRefundOrder } from "util/api";
import { PAID_STATUSES, isAwaitingDeposit, isRefunded, isShipped } from "util/orderStatus";
import { chargeOf } from "util/money";
import { useMoney } from "hook/useMoney";
import ItemQtyPicker, { anySelected, toItems } from "components/order/ItemQtyPicker";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";
import { orderStatusLabel } from "util/statusLabel";

/**
 * 화면의 상태 필터. 서버의 OrderStatus 이름 그대로 보낸다 — 번역하지 않는다.
 * AWAITING_RETURN 만 상태가 아니라 플래그다(서버 OrderDataService.AWAITING_RETURN_FILTER) —
 * 환불됐는데 물건이 아직 안 돌아온 주문.
 */
const STATUS_OPTIONS = [
    { value: "", labelKey: "admin.refunds.status.all" },
    { value: "PAID", labelKey: "admin.refunds.status.paid" },
    { value: "UNPAID", labelKey: "admin.refunds.status.unpaid" },
    { value: "PROCESSING", labelKey: "admin.refunds.status.processing" },
    { value: "DISPATCHED", labelKey: "admin.refunds.status.dispatched" },
    { value: "DELIVERED", labelKey: "admin.refunds.status.delivered" },
    { value: "COMPLETED", labelKey: "admin.refunds.status.completed" },
    { value: "REFUNDED", labelKey: "admin.refunds.status.refunded" },
    { value: "AWAITING_RETURN", labelKey: "admin.refunds.status.awaiting_return" },
    { value: "CANCELED", labelKey: "admin.refunds.status.canceled" },
    { value: "NOT_PROCESSED", labelKey: "admin.refunds.status.not_processed" },
];

const formatDate = (value) => formatDateTime(value, "-");

/**
 * 사전 문구의 {{amount}} 자리에 굵은 금액을 끼운다. 어순이 언어마다 달라 문구를 앞뒤로 쪼개 두지 않고,
 * 치환하지 않은 자리표시자를 기준으로 나눈다(interpolate 는 값이 없는 자리를 그대로 남긴다).
 */
const withAmount = (text, amount) => {
    const [before, after = ""] = text.split("{{amount}}");
    return <>{before}<b>{amount}</b>{after}</>;
};

/**
 * 관리자 환불.
 *
 * <p>고객 화면의 환불 버튼(RefundButton)과 <b>같은 엔드포인트</b>를 쓴다. 다른 점은
 * 서버가 {@code User-Role} 로 관리자임을 알고 두 규칙을 풀어 주는 것뿐이다 — 남의
 * 주문이어도, <b>발송된 뒤여도</b> 환불된다. 그래서 여기서는 결제된 모든 상태에 버튼이
 * 있고, 대신 확정 전에 한 번 더 묻는다.
 *
 * <p>환불 뒤에는 목록을 <b>서버에서 다시 읽는다.</b> 화면의 행만 바꾸면 서버가 거절했는데
 * 화면은 환불됨으로 보이는 경우가 생긴다.
 *
 * <p><b>반품 입고</b>도 여기서 한다. 발송된 뒤 환불된 주문은 물건이 돌아와야 재고가
 * 돌아온다 — 그 행에 "반품 입고" 가 붙고, 파손 등으로 다시 팔 수 없으면 재고 복원을 끄고
 * 입고만 기록한다. 아직 환불되지 않은 배송 완료 주문에도 붙는다(물건이 먼저 돌아온 경우).
 */
const Refunds = () => {
    const { t } = useTranslation();
    const money = useMoney();
    const [status, setStatus] = useState("PAID");
    const [trackingId, setTrackingId] = useState("");
    const [page, setPage] = useState(0);
    const [result, setResult] = useState({ content: [], totalPages: 0, totalElements: 0 });
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [confirming, setConfirming] = useState(null);   // trackingId
    const [reason, setReason] = useState("");
    const [receiving, setReceiving] = useState(null);     // trackingId — 반품 입고 확인 중
    const [restock, setRestock] = useState(true);
    const [picked, setPicked] = useState({});             // 열려 있는 패널의 품목별 수량
    const [confirmingDeposit, setConfirmingDeposit] = useState(null);   // trackingId — 입금 확인 중
    const [depositReference, setDepositReference] = useState("");

    /**
     * 품목별 "남은 수량" — 환불 패널은 qty − refundedQty, 입고 패널은 qty − shortQty − returnedQty.
     *
     * <p>shortQty(재고 초과 판매로 배정되지 못한 수, docs/53 §7)는 <b>나간 적이 없으므로</b> 돌아올 수도 없다 — 서버의
     * OrderProduct.remainingToReturn 과 같은 식이다. 예전에는 이 화면이 그 수까지 입고할 수 있다고 보였다.
     */
    const refundLines = (row) => (row.items ?? []).map((i) => ({
        id: i.orderProductId, name: i.name, size: i.size,
        max: Math.max(0, i.qty - (i.refundedQty ?? 0)),
    }));
    const returnLines = (row) => (row.items ?? []).map((i) => ({
        id: i.orderProductId, name: i.name, size: i.size,
        max: Math.max(0, i.qty - (i.shortQty ?? 0) - (i.returnedQty ?? 0)),
    }));

    const load = useCallback(async () => {
        setError("");
        try {
            const data = await adminListOrders({ status, trackingId, page, size: 20 });
            setResult({
                content: data?.content ?? [],
                totalPages: data?.totalPages ?? 0,
                totalElements: data?.totalElements ?? 0,
            });
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.refunds.error.load"));
        }
    }, [status, trackingId, page, t]);

    useEffect(() => {
        load();
    }, [load]);

    const refund = async (row) => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            const lines = refundLines(row);
            if (lines.length > 0 && !anySelected(lines, picked)) {
                setError(t("admin.refunds.error.pick_refund"));
                return;
            }
            await adminRefundOrder(row.trackingId, reason, toItems(lines, picked));
            setConfirming(null);
            setReason("");
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.refunds.error.refund"));
        } finally {
            setBusy(false);
        }
    };

    const receive = async (row) => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            const lines = returnLines(row);
            if (lines.length > 0 && !anySelected(lines, picked)) {
                setError(t("admin.refunds.error.pick_return"));
                return;
            }
            await adminReceiveReturn(row.trackingId, restock, toItems(lines, picked));
            setReceiving(null);
            setRestock(true);
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.refunds.error.receive"));
        } finally {
            setBusy(false);
        }
    };

    /** 아직 환불되지 않은 수량이 있는 결제된 주문. 품목 정보가 없는 옛 주문은 상태로만 본다. */
    /** 🔒 입금 확인 — 카드 결제 확인과 같은 무게다. 금액은 보내지 않는다. */
    const confirmDeposit = async (row) => {
        if (busy) return;
        setBusy(true);
        setError("");
        try {
            await adminConfirmDeposit(row.trackingId, depositReference.trim());
            setConfirmingDeposit(null);
            setDepositReference("");
            await load();
        } catch (e) {
            setError(e?.response?.data?.message ?? t("admin.refunds.error.deposit"));
        } finally {
            setBusy(false);
        }
    };

    /**
     * 입금 대기인데 아직 확인되지 않은 **무통장입금** 주문(확인되면 providerPaymentId 가 붙는다).
     * 포트원 가상계좌는 포트원 웹훅이 확인한다 — 사람이 확인하면 PG 의 진실과 갈린다.
     */
    const canConfirmDeposit = (row) =>
        isAwaitingDeposit(row.orderStatus) && !row.providerPaymentId && row.paymentMethod === "cash";

    const canRefund = (row) => PAID_STATUSES.has(String(row.orderStatus ?? "").toUpperCase())
        && (!row.items || row.items.length === 0 || refundLines(row).some((l) => l.max > 0));
    /** 물건이 밖에 있고 아직 안 돌아온 수량이 있는 주문 — 서버의 receiveReturn 규칙과 같다. */
    const canReceive = (row) => {
        const remaining = row.items && row.items.length > 0
            ? returnLines(row).some((l) => l.max > 0)
            : !row.returnReceivedAt;
        return remaining && (row.awaitingReturn || isShipped(row.orderStatus));
    };
    /** 재고 초과 판매로 배정되지 못한 수의 합 — 있으면 송장 수량을 줄여야 한다(docs/53 §7). */
    const shortTotal = (row) => (row.items ?? []).reduce((n, i) => n + (i.shortQty ?? 0), 0);

    const itemSummary = (row) => (row.items ?? []).map((i) => {
        const bits = [];
        if (i.shortQty) bits.push(t("admin.refunds.item.short", { count: i.shortQty }));
        if (i.refundedQty) bits.push(t("admin.refunds.item.refunded", { count: i.refundedQty }));
        if (i.returnedQty) bits.push(t("admin.refunds.item.returned", { count: i.returnedQty }));
        if (i.restockedQty) bits.push(t("admin.refunds.item.restocked", { count: i.restockedQty }));
        if (i.discardedQty) bits.push(t("admin.refunds.item.discarded", { count: i.discardedQty }));
        const detail = bits.length ? ` (${bits.join(t("admin.refunds.item.separator"))})` : "";
        return `${i.name}${i.size ? ` · ${i.size}` : ""}${i.skuCode ? ` [${i.skuCode}]` : ""} ×${i.qty}${detail}`;
    });

    return (
        <div className="p-6">
            <h1 className="mb-6 text-2xl font-bold">{t("admin.refunds.title")}</h1>

            {error && <p className="mb-4 text-red-600">{error}</p>}

            <div className="mb-4 flex flex-wrap items-end gap-3">
                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.refunds.filter.status")}</span>
                    <select
                        aria-label={t("admin.refunds.filter.status")}
                        value={status}
                        onChange={(e) => { setStatus(e.target.value); setPage(0); }}
                        className="mt-1 block rounded border border-slate-300 p-2"
                    >
                        {STATUS_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{t(o.labelKey)}</option>
                        ))}
                    </select>
                </label>
                <label className="block">
                    <span className="text-sm text-slate-700">{t("admin.refunds.filter.tracking_id")}</span>
                    <input
                        aria-label={t("admin.refunds.filter.tracking_id")}
                        value={trackingId}
                        onChange={(e) => { setTrackingId(e.target.value.trim()); setPage(0); }}
                        placeholder={t("admin.refunds.filter.tracking_placeholder")}
                        className="mt-1 block w-72 rounded border border-slate-300 p-2"
                    />
                </label>
                <span className="pb-2 text-sm text-slate-500">{t("admin.refunds.total", { count: result.totalElements })}</span>
            </div>

            <table className="w-full border-collapse text-sm">
                <thead>
                    <tr className="border-b bg-slate-50 text-left">
                        <th className="p-2">{t("admin.refunds.col.order")}</th>
                        <th className="p-2">{t("admin.refunds.col.customer")}</th>
                        <th className="p-2">{t("admin.refunds.col.amount")}</th>
                        <th className="p-2">{t("admin.refunds.col.payment")}</th>
                        <th className="p-2">{t("admin.refunds.col.status")}</th>
                        <th className="p-2">{t("admin.refunds.col.paid_at")}</th>
                        <th className="p-2">{t("admin.refunds.col.refund")}</th>
                    </tr>
                </thead>
                <tbody>
                    {result.content.length === 0 && (
                        <tr><td colSpan={7} className="p-6 text-center text-slate-500">{t("admin.refunds.empty")}</td></tr>
                    )}
                    {result.content.map((row) => (
                        <tr key={row.orderId} className="border-b align-top">
                            <td className="p-2">
                                <div className="font-mono">#{row.orderId}</div>
                                <div className="text-xs text-slate-500">{row.trackingId}</div>
                            </td>
                            <td className="p-2">{row.customerEmail ?? `#${row.customerId ?? "-"}`}</td>
                            <td className="p-2">
                                <div>{money(Number(row.total ?? 0))}</div>
                                {row.currency && (
                                    <div className="text-xs text-slate-500">
                                        {t("admin.refunds.charged", { amount: money(chargeOf(row).amount, chargeOf(row).currency) })}
                                    </div>
                                )}
                                {Number(row.refundedAmount ?? 0) > 0 && (
                                    <div className="text-xs text-red-600">
                                        {t("admin.refunds.refunded_amount", { amount: money(Number(row.refundedAmount)) })}
                                    </div>
                                )}
                                {itemSummary(row).map((line, i) => (
                                    <div key={i} className="text-xs text-slate-500">{line}</div>
                                ))}
                                {shortTotal(row) > 0 && (
                                    // 배송은 결제 직후 주문을 읽어 만들어진다 — 부족 기록보다 먼저였다면 배송 수량이 원래 수량
                                    // 그대로다. 송장을 낼 때 이 수만큼 빼야 한다(docs/53 §7 남은 한계).
                                    <div role="status" className="mt-1 rounded bg-red-50 px-2 py-1 text-xs font-semibold text-red-700">
                                        {t("admin.refunds.short_warning", { count: shortTotal(row) })}
                                    </div>
                                )}
                            </td>
                            <td className="p-2">
                                <div>{row.paymentMethod ?? "-"}</div>
                                {row.paymentProvider && (
                                    <div className="text-xs text-slate-500">
                                        {row.paymentProvider} · {row.providerPaymentId}
                                    </div>
                                )}
                            </td>
                            <td className="p-2">
                                <span className={isRefunded(row.orderStatus) ? "text-red-600" : ""}>
                                    {orderStatusLabel(t, row.orderStatus)}
                                </span>
                                {row.awaitingReturn && (
                                    <div className="text-xs text-amber-700">{t("admin.refunds.status.awaiting_return")}</div>
                                )}
                                {isAwaitingDeposit(row.orderStatus) && (
                                    <div className="text-xs text-amber-700">
                                        {row.paymentMethod === "portone"
                                            ? t("admin.refunds.deposit.virtual_account")
                                            : t("admin.refunds.deposit.depositor", { name: row.depositorName ?? "-" })}
                                        {" · "}{t("admin.refunds.deposit.deadline", { date: formatDate(row.depositDeadline) })}
                                        {row.providerPaymentId && ` · ${t("admin.refunds.deposit.confirmed_pending")}`}
                                    </div>
                                )}
                                {row.returnReceivedAt && (
                                    <div className="text-xs text-slate-500">{t("admin.refunds.returned_at", { date: formatDate(row.returnReceivedAt) })}</div>
                                )}
                                {row.stockRestoredAt && (
                                    <div className="text-xs text-slate-500">{t("admin.refunds.restocked_at", { date: formatDate(row.stockRestoredAt) })}</div>
                                )}
                            </td>
                            <td className="p-2">{formatDate(row.paidCreatedAt ?? row.createdAt)}</td>
                            <td className="p-2">
                                {isRefunded(row.orderStatus) && (
                                    <div className="text-xs text-slate-600">
                                        <div>{formatDate(row.refundedAt)}</div>
                                        <div className="font-mono">{row.refundId}</div>
                                    </div>
                                )}
                                {canConfirmDeposit(row) && confirmingDeposit !== row.trackingId && (
                                    <button
                                        type="button"
                                        disabled={busy}
                                        onClick={() => { setConfirmingDeposit(row.trackingId); setDepositReference(""); }}
                                        className="mr-2 rounded border border-emerald-600 px-3 py-1 text-emerald-700 hover:bg-emerald-600 hover:text-white disabled:opacity-40"
                                    >
                                        {t("admin.refunds.deposit.confirm")}
                                    </button>
                                )}
                                {canConfirmDeposit(row) && confirmingDeposit === row.trackingId && (
                                    <div className="mb-2 w-64 rounded border border-emerald-300 bg-emerald-50 p-2">
                                        <p className="text-xs text-slate-800">
                                            {withAmount(t("admin.refunds.deposit.prompt"),
                                                money(chargeOf(row).amount, chargeOf(row).currency))}
                                        </p>
                                        <input
                                            aria-label={t("admin.refunds.deposit.reference")}
                                            value={depositReference}
                                            onChange={(e) => setDepositReference(e.target.value)}
                                            maxLength={100}
                                            placeholder={t("admin.refunds.deposit.reference_placeholder")}
                                            className="mt-1 w-full rounded border border-slate-300 p-1 text-xs"
                                        />
                                        <div className="mt-2 flex gap-2">
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => confirmDeposit(row)}
                                                className="flex-1 rounded bg-emerald-600 px-2 py-1 text-xs text-white disabled:opacity-40"
                                            >
                                                {busy ? t("admin.refunds.processing") : t("admin.refunds.deposit.submit")}
                                            </button>
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => setConfirmingDeposit(null)}
                                                className="flex-1 rounded border px-2 py-1 text-xs"
                                            >
                                                {t("common.cancel")}
                                            </button>
                                        </div>
                                    </div>
                                )}
                                {canRefund(row) && confirming !== row.trackingId && (
                                    <button
                                        type="button"
                                        disabled={busy}
                                        onClick={() => { setConfirming(row.trackingId); setReceiving(null); setReason(""); setPicked({}); }}
                                        className="rounded border border-red-500 px-3 py-1 text-red-600 hover:bg-red-500 hover:text-white disabled:opacity-40"
                                    >
                                        {t("admin.refunds.refund")}
                                    </button>
                                )}
                                {canReceive(row) && receiving !== row.trackingId && (
                                    <button
                                        type="button"
                                        disabled={busy}
                                        onClick={() => { setReceiving(row.trackingId); setConfirming(null); setRestock(true); setPicked({}); }}
                                        className="ml-2 rounded border border-amber-600 px-3 py-1 text-amber-700 hover:bg-amber-600 hover:text-white disabled:opacity-40"
                                    >
                                        {t("admin.refunds.receive")}
                                    </button>
                                )}
                                {canReceive(row) && receiving === row.trackingId && (
                                    <div className="mt-2 w-64 rounded border border-amber-300 bg-amber-50 p-2">
                                        <p className="text-xs text-slate-800">
                                            {t("admin.refunds.receive.prompt")}
                                        </p>
                                        <ItemQtyPicker lines={returnLines(row)} value={picked} onChange={setPicked} label={t("admin.refunds.receive.qty")} disabled={busy} />
                                        <label className="mt-1 flex items-center gap-2 text-xs text-slate-800">
                                            <input
                                                type="checkbox"
                                                aria-label={t("admin.refunds.receive.restock")}
                                                checked={restock}
                                                onChange={(e) => setRestock(e.target.checked)}
                                            />
                                            {t("admin.refunds.receive.restock_hint")}
                                        </label>
                                        <div className="mt-2 flex gap-2">
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => receive(row)}
                                                className="flex-1 rounded bg-amber-600 px-2 py-1 text-xs text-white disabled:opacity-40"
                                            >
                                                {busy ? t("admin.refunds.processing") : t("admin.refunds.receive.submit")}
                                            </button>
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => setReceiving(null)}
                                                className="flex-1 rounded border px-2 py-1 text-xs"
                                            >
                                                {t("common.cancel")}
                                            </button>
                                        </div>
                                    </div>
                                )}
                                {canRefund(row) && confirming === row.trackingId && (
                                    <div className="w-64 rounded border border-red-300 bg-red-50 p-2">
                                        <p className="text-xs text-slate-800">
                                            {withAmount(
                                                t("admin.refunds.refund.prompt", {
                                                    provider: row.paymentProvider ?? t("admin.refunds.refund.default_provider"),
                                                }),
                                                money(chargeOf(row).amount - Number(row.refundedChargedAmount ?? 0), chargeOf(row).currency))}
                                        </p>
                                        <ItemQtyPicker lines={refundLines(row)} value={picked} onChange={setPicked} label={t("admin.refunds.refund.qty")} disabled={busy} />
                                        <input
                                            aria-label={t("admin.refunds.refund.reason")}
                                            value={reason}
                                            onChange={(e) => setReason(e.target.value)}
                                            maxLength={200}
                                            placeholder={t("admin.refunds.refund.reason_placeholder")}
                                            className="mt-1 w-full rounded border border-slate-300 p-1 text-xs"
                                        />
                                        <div className="mt-2 flex gap-2">
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => refund(row)}
                                                className="flex-1 rounded bg-red-500 px-2 py-1 text-xs text-white disabled:opacity-40"
                                            >
                                                {busy ? t("admin.refunds.processing") : t("admin.refunds.refund.submit")}
                                            </button>
                                            <button
                                                type="button"
                                                disabled={busy}
                                                onClick={() => setConfirming(null)}
                                                className="flex-1 rounded border px-2 py-1 text-xs"
                                            >
                                                {t("common.cancel")}
                                            </button>
                                        </div>
                                    </div>
                                )}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            <div className="mt-4 flex items-center gap-3">
                <button
                    type="button"
                    disabled={page <= 0}
                    onClick={() => setPage((p) => p - 1)}
                    className="rounded border px-3 py-1 disabled:opacity-40"
                >
                    {t("admin.refunds.prev")}
                </button>
                <span className="text-sm text-slate-600">
                    {result.totalPages === 0 ? 0 : page + 1} / {result.totalPages}
                </span>
                <button
                    type="button"
                    disabled={page + 1 >= result.totalPages}
                    onClick={() => setPage((p) => p + 1)}
                    className="rounded border px-3 py-1 disabled:opacity-40"
                >
                    {t("admin.refunds.next")}
                </button>
            </div>
        </div>
    );
};

export default Refunds;
