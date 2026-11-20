import { useState } from "react";
import { useMoney } from "hook/useMoney";
import toast from "react-hot-toast";

import { postRequest } from "util/api";
import { canRequestRefund, isDelivered, isPaid, isRefunded } from "util/orderStatus";
import { chargeOf } from "util/money";
import ItemQtyPicker, { anySelected, toItems } from "components/order/ItemQtyPicker";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 주문 상세의 환불 요청.
 *
 * <p>보내는 것은 <b>어느 주문·왜</b> 뿐이다. 금액도 결제 참조도 보내지 않는다 — 서버가
 * DB 의 주문 총액을 제공자에게 돌려달라고 하고, 응답을 대조한다(docs/49 §10).
 *
 * <p>상태별로 셋 중 하나를 그린다:
 * <ul>
 *   <li>발송 전(PAID·PROCESSING): 버튼</li>
 *   <li>발송 후: 고객센터 안내 — 서버가 어차피 거절하지만, 눌러 보고 거절당하는 것보다
 *       처음부터 없는 편이 낫다</li>
 *   <li>환불됨: 완료 표시</li>
 * </ul>
 *
 * <p>[JA] 注文詳細の返金リクエスト。
 * 送るのは <b>どの注文・なぜ</b> だけだ。金額も決済参照も送らない — サーバーが
 * DB の注文総額をプロバイダーに返すよう求め、応答を照合する(docs/49 §10)。
 * 状態別に三つのうち一つを描く: 発送前(PAID・PROCESSING)はボタン、発送後はカスタマーセンター案内
 * (サーバーがどのみち拒否するが、押して拒否されるより最初から無いほうがよい)、返金済みは完了表示。
 */
export default function RefundButton({ order, onRefunded }) {
    const { t } = useTranslation();
    const money = useMoney();
    const [reason, setReason] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [confirming, setConfirming] = useState(false);
    const [picked, setPicked] = useState({});

    const status = order?.orderStatus;
    // 부분 환불(docs/49 §10) — 줄마다 아직 환불되지 않은 수량이 남아 있으면 고를 수 있다.
    // [JA] 部分返金(docs/49 §10) — 行ごとにまだ返金されていない数量が残っていれば選べる。
    const lines = (order?.products ?? []).map((p) => ({
        id: p.id, name: p.name, size: p.size,
        max: Math.max(0, Number(p.qty ?? 0) - Number(p.refundedQty ?? 0)),
    }));
    const partiallyRefunded = Number(order?.refundedAmount ?? 0) > 0;

    if (isRefunded(status)) {
        return (
            <div className="mt-4 rounded border border-slate-300 bg-slate-50 p-3 text-sm text-slate-700">
                {t("refund.done")}
                {order?.refundId && (
                    <div className="mt-1 text-xs text-slate-500">{t("refund.reference", { id: order.refundId })}</div>
                )}
                {order?.awaitingReturn && (
                    <div className="mt-1 text-xs text-amber-700">
                        {t("refund.awaiting_return")}
                    </div>
                )}
                {!order?.awaitingReturn && order?.returnReceivedAt && (
                    <div className="mt-1 text-xs text-slate-500">{t("refund.return_received")}</div>
                )}
            </div>
        );
    }

    if (!isPaid(status)) {
        return null;
    }

    if (!canRequestRefund(status)) {
        // 도착한 물건과 오는 중인 물건은 다른 말이 필요하다. 도착했으면 돌려보내야 환불된다.
        // [JA] 届いた品物と配送中の品物では別の言い方が要る。届いたなら返送しないと返金されない。
        return (
            <div className="mt-4 rounded border border-slate-300 bg-slate-50 p-3 text-sm text-slate-700">
                {isDelivered(status)
                    ? t("refund.delivered_hint")
                    : t("refund.shipped_hint")}
            </div>
        );
    }

    // 무통장입금은 돌려줄 계좌가 필요하다 — 서버도 고객의 직접 환불을 거절한다(docs/49 §11).
    // [JA] 銀行振込は返金先の口座が必要だ — サーバーも顧客による直接返金を拒否する(docs/49 §11)。
    if (order?.paymentMethod === "cash") {
        return (
            <div className="mt-4 rounded border border-slate-300 bg-slate-50 p-3 text-sm text-slate-700">
                {t("refund.cash_hint")}
            </div>
        );
    }

    const submit = async () => {
        if (!anySelected(lines, picked)) {
            toast.error(t("refund.error.no_qty"));
            return;
        }
        setSubmitting(true);
        try {
            const body = { trackingId: order.trackingId, reason };
            const items = toItems(lines, picked);
            if (items) body.items = items;   // 비우면 남은 전부 — 서버 기본과 같다 ／ [JA] 空なら残り全部 — サーバーのデフォルトと同じ
            const res = await postRequest("/order-service/api/order/refund", body);
            toast.success(res?.data?.message ?? t("refund.success"));
            setConfirming(false);
            onRefunded?.(res?.data);
        } catch (error) {
            // 400 은 규칙에 걸린 거절(서버가 이유를 준다), 그 밖은 처리 실패다.
            // [JA] 400 は規則に引っかかった拒否(サーバーが理由を返す)、それ以外は処理失敗だ。
            toast.error(error?.response?.data?.message ?? t("refund.error.failed"));
        } finally {
            setSubmitting(false);
        }
    };

    if (!confirming) {
        return (
            <div>
                {partiallyRefunded && (
                    <div className="mt-4 text-xs text-slate-600">
                        {t("refund.partial_so_far", { amount: money(order.refundedAmount) })}
                    </div>
                )}
                <button
                    type="button"
                    onClick={() => { setPicked({}); setConfirming(true); }}
                    className="mt-2 w-full rounded border border-red-500 py-2 text-red-600 transition-all duration-300 hover:bg-red-500 hover:text-white"
                >
                    {partiallyRefunded ? t("refund.request_rest") : t("refund.request")}
                </button>
            </div>
        );
    }

    return (
        <div className="mt-4 rounded border border-red-300 bg-red-50 p-3">
            <p className="text-sm text-slate-800">
                {t("refund.confirm_before")} <b>{money(chargeOf(order).amount, chargeOf(order).currency)}</b>{" "}
                {t("refund.confirm_after")}
            </p>
            <ItemQtyPicker lines={lines} value={picked} onChange={setPicked} label={t("refund.qty_label")} disabled={submitting} />
            {lines.length > 0 && toItems(lines, picked) && (
                <p className="mt-1 text-xs text-slate-600">
                    {t("refund.partial_hint")}
                </p>
            )}
            <input
                className="mt-2 w-full rounded border px-2 py-1.5 text-sm outline-none"
                placeholder={t("refund.reason_placeholder")}
                aria-label={t("refund.reason_label")}
                maxLength={200}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
            />
            <div className="mt-2 flex gap-2">
                <button
                    type="button"
                    disabled={submitting}
                    onClick={submit}
                    className="flex-1 rounded bg-red-500 py-2 text-white hover:scale-95 transition-all duration-300 disabled:opacity-50"
                >
                    {submitting ? t("order.detail.processing") : t("refund.confirm")}
                </button>
                <button
                    type="button"
                    disabled={submitting}
                    onClick={() => setConfirming(false)}
                    className="flex-1 rounded border py-2 hover:bg-white"
                >
                    {t("order.detail.back")}
                </button>
            </div>
        </div>
    );
}
