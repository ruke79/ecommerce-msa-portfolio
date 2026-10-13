import React, { useEffect, useState } from "react";

import { getOrderTimeline } from "util/api";
import { formatDateTime } from "util/datetime";
import { orderStatusLabel } from "util/statusLabel";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 주문의 진행 기록 — 상태가 언제 · 무엇 때문에 바뀌었는가 (docs/76).
 *
 * <p>주문 상세(order-service 의 DB)가 아니라 **읽기 모델**(saga-projection 이 상태 변경 이벤트를 접은 것)에서 읽는다.
 * 그래서 주문 상세와 몇 초 어긋날 수 있다 — 상태가 바뀐 것을 보면 다시 읽고, 서버가 "대기 복제본에서 답했다"(stale) 고
 * 하면 그렇다고 말한다.
 *
 * <p>없으면 없는 대로 — 404(아직 이벤트가 없다 · 옛 주문) · 503(프로젝션 준비 중)은 오류가 아니다. 주문 상세는
 * 이 컴포넌트 없이도 완전하므로 **아무것도 그리지 않는다.** 로딩도 보여 주지 않는다 — 깜빡임만 더한다.
 */
export default function OrderTimeline({ orderId, orderStatus }) {
    const { t } = useTranslation();
    const [view, setView] = useState(null);

    useEffect(() => {
        if (!orderId) return undefined;
        let alive = true;
        getOrderTimeline(orderId)
            .then((data) => { if (alive) setView(data); })
            .catch(() => { if (alive) setView(null); });
        return () => { alive = false; };
        // orderStatus 가 바뀌면(결제 확인 · 배송 · 확정) 기록도 늘었을 것이다 — 그때 다시 읽는다.
    }, [orderId, orderStatus]);

    const entries = view?.timeline ?? [];
    if (entries.length === 0) return null;

    return (
        <section className="mb-4 rounded border border-slate-200 p-3" data-testid="order-timeline">
            <p className="mb-2 text-sm font-semibold text-slate-700">{t("order.timeline.title")}</p>
            <ol className="space-y-1 text-sm">
                {entries.map((e, i) => (
                    <li key={`${e.occurredAt}-${e.status}-${i}`} className="flex flex-wrap items-baseline gap-x-3"
                        data-status={e.status}>
                        <time className="text-xs text-slate-500" dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time>
                        <span className="font-medium text-slate-800">{orderStatusLabel(t, e.status)}</span>
                        <span className="text-xs text-slate-500">{sourceLabel(t, e.source)}</span>
                    </li>
                ))}
            </ol>
            {view?.stale && (
                <p className="mt-2 text-xs text-slate-500" role="note">{t("order.timeline.stale")}</p>
            )}
        </section>
    );
}

/** 출처 코드 → 이름. 모르는 코드(서버에 새 출처가 생겼다)는 코드 그대로 — 빈칸보다 낫다(statusLabel 과 같은 규칙). */
export function sourceLabel(t, source) {
    if (!source) return "";
    const key = `order.timeline.source.${source}`;
    const text = t(key);
    return text === key ? String(source) : text;
}
