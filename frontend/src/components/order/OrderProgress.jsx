import { useTranslation } from "i18n/I18nProvider";
import { isCanceled, isCompleted, isRefunded } from "util/orderStatus";

/** 화면의 다섯 단계와, 주문 상태가 그중 어디에 서 있는가. */
export const STEPS = ["payment", "confirmed", "preparing", "shipping", "delivered"];

const STEP_OF = {
    NOT_PROCESSED: 0,
    UNPAID: 0,
    PAID: 1,
    PROCESSING: 2,
    DISPATCHED: 3,
    DELIVERED: 4,
    COMPLETED: 4,
};

export function stepOf(orderStatus) {
    return STEP_OF[String(orderStatus ?? "").trim().toUpperCase()] ?? 0;
}

/**
 * 주문이 어디까지 왔는가 — 결제 · 주문 확정 · 상품 준비 · 배송 중 · 배송 완료.
 *
 * <p>결제 확인 직후에는 주문이 아직 {@code NOT_PROCESSED} 다(사가가 Kafka 왕복 뒤에 PAID
 * 로 바꾼다, useOrderSettling). 그동안은 첫 단계를 끝난 것으로, 둘째 단계를 진행 중으로
 * 그리고 "확정하는 중" 이라고 말한다. 시한을 넘기면 "늦어진다" 고 말한다 — "완료" 라고
 * 거짓말하지 않는다.
 *
 * <p>취소·환불은 단계가 아니라 결과라 띠 하나로 대신한다.
 */
export default function OrderProgress({ order, settling = false, timedOut = false }) {
    const { t } = useTranslation();
    const status = order?.orderStatus;

    if (isCanceled(status) || isRefunded(status)) {
        return (
            <div className="mb-4 rounded border border-slate-300 bg-slate-50 p-3 text-sm text-slate-700"
                 data-testid="order-progress" role="status">
                {t(isRefunded(status) ? "order.progress.refunded" : "order.progress.canceled")}
            </div>
        );
    }

    // 확정을 기다리는 동안은 결제(0)는 끝났고 확정(1)이 진행 중이다.
    const current = settling || timedOut ? 1 : stepOf(status);
    const waiting = settling || timedOut;

    let note;
    if (settling) note = t("order.progress.settling");
    else if (timedOut) note = t("order.progress.settle_timeout");
    else if (status === "UNPAID") note = t("order.progress.awaiting_deposit");
    else if (current === 0) note = t("order.progress.awaiting_payment");
    else if (current === 1) note = t("order.progress.preparing_hint");
    // 구매 확정(docs/25 §12)은 다섯 단계 뒤의 마무리라 띠에는 칸이 없다 — 한 줄로 말한다.
    else if (isCompleted(status)) note = t("order.progress.completed_hint");

    return (
        <div className="mb-4" data-testid="order-progress">
            <p className="mb-2 text-sm font-semibold text-slate-700">{t("order.progress.title")}</p>
            <ol className="flex items-center gap-1 text-xs">
                {STEPS.map((step, i) => {
                    const state = i < current ? "done" : i === current ? "current" : "todo";
                    // 이미 지난 단계와 지금 단계 모두 "여기까지 왔다" 로 칠한다. 지금 단계만 굵게.
                    const reached = i <= current;
                    return (
                        <li
                            key={step}
                            data-step={step}
                            data-state={state}
                            aria-current={i === current ? "step" : undefined}
                            className={`flex-1 rounded px-2 py-1.5 text-center ${
                                reached ? "bg-slate-800 text-white" : "bg-slate-100 text-slate-400"
                            } ${i === current ? "font-bold" : ""}`}
                        >
                            {t(`order.progress.${step}`)}
                        </li>
                    );
                })}
            </ol>
            {note && (
                <p
                    className={`mt-2 text-sm ${timedOut ? "text-amber-700" : "text-slate-600"}`}
                    role={timedOut ? "alert" : "status"}
                    aria-live="polite"
                >
                    {note}
                </p>
            )}
        </div>
    );
}
