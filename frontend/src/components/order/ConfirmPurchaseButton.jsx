import { useState } from "react";
import toast from "react-hot-toast";

import { confirmPurchase } from "util/api";
import { canConfirmPurchase, isCompleted } from "util/orderStatus";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 주문 상세의 구매 확정 (docs/25 §12) — 주문의 마지막 단계.
 *
 * <p>🐛 {@code COMPLETED} 는 서버에 선언만 있고 <b>그 값을 넣는 코드가 없었다.</b> 배달 완료가
 * 끝이었고, 프로필의 "구매 확정" 필터는 영원히 비어 있었다. 이제 배달 완료된 주문에 버튼이
 * 뜨고, 누르지 않아도 기한이 지나면 서버가 자동으로 확정한다.
 *
 * <p>상태별로 셋 중 하나를 그린다:
 * <ul>
 *   <li>배달 완료(DELIVERED): 안내 + 버튼. 누르면 한 번 더 묻는다 — 되돌릴 수 없다.</li>
 *   <li>확정됨(COMPLETED): 확정 시각. 자동 확정이면 그렇다고 따로 말한다 — 손님이 누른 적이
 *       없는데 상태가 바뀌어 있으면 이유를 알아야 한다.</li>
 *   <li>그 밖: 아무것도 그리지 않는다.</li>
 * </ul>
 *
 * <p>보내는 것은 주문 번호뿐이다. 서버가 소유권과 상태를 다시 본다.
 */
export default function ConfirmPurchaseButton({ order, onConfirmed }) {
    const { t } = useTranslation();
    const [confirming, setConfirming] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const status = order?.orderStatus;

    if (isCompleted(status)) {
        return (
            <div className="mt-4 rounded border border-green-300 bg-green-50 p-3 text-sm text-slate-700"
                 role="status" data-testid="purchase-completed">
                {t("purchase_confirm.completed")}
                {order?.completedAt && (
                    <div className="mt-1 text-xs text-slate-500">{t("purchase_confirm.completed_at", { time: formatDateTime(order.completedAt) })}</div>
                )}
                {order?.autoCompleted && (
                    <div className="mt-1 text-xs text-slate-500">
                        {t("purchase_confirm.auto")}
                    </div>
                )}
            </div>
        );
    }

    if (!canConfirmPurchase(status)) {
        return null;
    }

    const submit = async () => {
        setSubmitting(true);
        try {
            const res = await confirmPurchase(order.id);
            toast.success(res?.message ?? t("purchase_confirm.done"));
            setConfirming(false);
            onConfirmed?.(res);
        } catch (error) {
            // 400 은 규칙에 걸린 거절(서버가 이유를 준다), 404 는 내 주문이 아니다.
            toast.error(error?.response?.data?.message ?? t("purchase_confirm.failed"));
        } finally {
            setSubmitting(false);
        }
    };

    if (!confirming) {
        return (
            <div className="mt-4 rounded border border-slate-300 bg-slate-50 p-3">
                <p className="text-sm text-slate-800">{t("purchase_confirm.question")}</p>
                <p className="mt-1 text-xs text-slate-600">
                    {t("purchase_confirm.hint")}
                </p>
                <button
                    type="button"
                    onClick={() => setConfirming(true)}
                    className="mt-2 w-full rounded bg-green-600 py-2 text-white transition-all duration-300 hover:bg-green-700"
                >
                    {t("purchase_confirm.button")}
                </button>
            </div>
        );
    }

    return (
        <div className="mt-4 rounded border border-green-300 bg-green-50 p-3">
            <p className="text-sm text-slate-800">
                {t("purchase_confirm.ask")} <b>{t("purchase_confirm.irreversible")}</b>
            </p>
            <div className="mt-2 flex gap-2">
                <button
                    type="button"
                    disabled={submitting}
                    onClick={submit}
                    className="flex-1 rounded bg-green-600 py-2 text-white hover:scale-95 transition-all duration-300 disabled:opacity-50"
                >
                    {submitting ? t("order.detail.processing") : t("purchase_confirm.confirm")}
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
