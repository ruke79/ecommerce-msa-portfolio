import { useEffect, useState } from "react";
import { useMoney } from "hook/useMoney";
import toast from "react-hot-toast";

import { cancelBankTransfer, getBankTransferNotice, startBankTransfer } from "util/api";

import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const formatDeadline = (value) => {
    if (!value) return "-";
    // 읽을 수 없으면 원문이라도 보여 준다 — 입금 기한은 사라지면 안 되는 정보다.
    return formatDateTime(value, String(value));
};

/**
 * 무통장입금 — 주문 상세의 결제 자리에 그려지는 상태 기계의 고객 쪽 (docs/49 §11).
 *
 * <ul>
 *   <li>{@code NOT_PROCESSED}: 입금자명을 받아 "입금 안내 받기". 서버가 UNPAID 로 바꾸고
 *       계좌·금액·기한을 돌려준다.</li>
 *   <li>{@code UNPAID}: 안내를 다시 보여 준다(계좌·금액·기한·입금자명) + 입금 전 취소.</li>
 * </ul>
 *
 * <p>금액은 서버의 청구액이다(포트원처럼 KRW 일 수 있다). 화면이 total 을 환산하지 않는다.
 * 입금 확인은 관리자가 하고, 확인되면 주문이 결제 완료로 바뀐다 — 여기서는 기다린다고만
 * 말한다.
 */
export default function BankTransfer({ order, onChanged }) {
    const { t } = useTranslation();
    const money = useMoney();
    const [depositorName, setDepositorName] = useState(order?.depositorName ?? "");
    const [notice, setNotice] = useState(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");

    const status = order?.orderStatus;
    const waiting = status === "UNPAID";

    useEffect(() => {
        let alive = true;
        if (!waiting || !order?.trackingId) return undefined;
        getBankTransferNotice(order.trackingId)
            .then((n) => { if (alive) setNotice(n); })
            .catch(() => { if (alive) setError(t("bank_transfer.error.load")); });
        return () => { alive = false; };
    }, [waiting, order?.trackingId, t]);

    const start = async () => {
        if (!depositorName.trim()) {
            toast.error(t("bank_transfer.error.depositor_required"));
            return;
        }
        setBusy(true);
        setError("");
        try {
            const n = await startBankTransfer(order.trackingId, depositorName.trim());
            setNotice(n);
            toast.success(t("bank_transfer.started"));
            onChanged?.(n);
        } catch (e) {
            setError(e?.response?.data?.message ?? t("bank_transfer.error.start"));
        } finally {
            setBusy(false);
        }
    };

    const cancel = async () => {
        setBusy(true);
        setError("");
        try {
            await cancelBankTransfer(order.trackingId);
            toast.success(t("bank_transfer.canceled"));
            onChanged?.({ orderStatus: "CANCELED" });
        } catch (e) {
            setError(e?.response?.data?.message ?? t("bank_transfer.error.cancel"));
        } finally {
            setBusy(false);
        }
    };

    if (!waiting && status !== "NOT_PROCESSED") return null;
    // 포트원 가상계좌는 결제창이 발급한다 — 여기서 입금자명을 받아 안내를 만들 일이 없다.
    if (!waiting && order?.paymentMethod && order.paymentMethod !== "cash") return null;

    if (!waiting && !notice) {
        return (
            <div className="rounded border border-slate-300 bg-slate-50 p-3 text-sm text-slate-800">
                <p className="font-semibold">{t("payment.method.cash.name")}</p>
                <p className="mt-1 text-xs text-slate-600">
                    {t("bank_transfer.intro")}
                </p>
                <input
                    aria-label={t("bank_transfer.depositor")}
                    value={depositorName}
                    onChange={(e) => setDepositorName(e.target.value)}
                    maxLength={50}
                    placeholder={t("bank_transfer.depositor_placeholder")}
                    className="mt-2 w-full rounded border border-slate-300 p-2"
                />
                {error && <p className="mt-1 text-xs text-red-600" role="alert">{error}</p>}
                <button
                    type="button"
                    disabled={busy}
                    onClick={start}
                    className="mt-2 w-full rounded bg-slate-800 py-3 font-semibold text-white hover:bg-slate-900 disabled:opacity-60"
                >
                    {busy ? t("bank_transfer.starting") : t("bank_transfer.start")}
                </button>
            </div>
        );
    }

    const n = notice ?? {};
    const viaPortOne = n.provider === "PORTONE" || order?.paymentMethod === "portone";
    return (
        <div className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-slate-800" data-testid="bank-transfer-notice">
            <p className="font-semibold">{viaPortOne ? t("bank_transfer.waiting_vbank") : t("bank_transfer.waiting")}</p>
            <dl className="mt-2 grid grid-cols-3 gap-y-1 text-xs">
                <dt className="text-slate-500">{t("bank_transfer.bank")}</dt><dd className="col-span-2">{n.bank ?? "-"}</dd>
                <dt className="text-slate-500">{t("bank_transfer.account")}</dt><dd className="col-span-2 font-mono">{n.account ?? "-"}</dd>
                <dt className="text-slate-500">{t("bank_transfer.holder")}</dt><dd className="col-span-2">{n.holder ?? "-"}</dd>
                <dt className="text-slate-500">{t("bank_transfer.amount")}</dt>
                <dd className="col-span-2 font-semibold">
                    {n.amount != null && n.currency ? money(n.amount, n.currency) : "-"}
                </dd>
                {!viaPortOne && (
                    <>
                        <dt className="text-slate-500">{t("bank_transfer.depositor")}</dt><dd className="col-span-2">{n.depositorName ?? order?.depositorName ?? "-"}</dd>
                    </>
                )}
                <dt className="text-slate-500">{t("bank_transfer.deadline")}</dt><dd className="col-span-2">{formatDeadline(n.depositDeadline ?? order?.depositDeadline)}</dd>
            </dl>
            <p className="mt-2 text-xs text-slate-600">
                {viaPortOne
                    ? t("bank_transfer.hint_portone")
                    : t("bank_transfer.hint")}
            </p>
            {error && <p className="mt-1 text-xs text-red-600" role="alert">{error}</p>}
            <button
                type="button"
                disabled={busy}
                onClick={cancel}
                className="mt-2 w-full rounded border border-slate-400 py-2 text-slate-700 hover:bg-white disabled:opacity-60"
            >
                {busy ? t("order.detail.processing") : t("bank_transfer.cancel")}
            </button>
        </div>
    );
}
