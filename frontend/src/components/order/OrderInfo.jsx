import {
    CheckBadgeIcon,
    ChevronRightIcon,
    XCircleIcon,
} from "@heroicons/react/24/solid";

import { useTranslation } from "i18n/I18nProvider";
import { isCanceled, isCompleted, isDelivered, isPaid, isRefunded } from "util/orderStatus";
import { orderStatusLabel } from "util/statusLabel";

/**
 * 상태 글자의 색. 🐛 예전에는 {@code order.status == "COMPLETED"} 였다 — DTO 의 필드는
 * {@code orderStatus} 라 그 비교는 언제나 거짓이었고, 모든 주문이 빨간 글자였다.
 * 끝난 것(도착·확정)은 초록, 접힌 것(취소·환불)은 빨강, 진행 중은 회색이다.
 */
function statusTone(orderStatus) {
    if (isCompleted(orderStatus) || isDelivered(orderStatus)) return "text-green-600";
    if (isCanceled(orderStatus) || isRefunded(orderStatus)) return "text-red-600";
    return "text-slate-700";
}

const OrderInfo = ({ order }) => {
    const { t } = useTranslation();

    return (
        <div className="border-b pb-4 mb-2">
            <div className="flex items-center text-sm text-slate-600">
                <span>{t("browse.home")}</span>
                <ChevronRightIcon className="w-4 mx-2 h-4" />
                <span>{t("order.breadcrumb.orders")}</span>
                <ChevronRightIcon className="w-4 mx-2 h-4" />
                <span>{order.trackingId}</span>
            </div>

            <div className="mt-3 flex items-center">
                <span className="font-semibold mr-2">{t("order.payment_status")}</span>
                {/* 🐛 `== "PAID"` 였다 — 배송 중(DISPATCHED)인 주문이 결제 상태 X 로 보였다.
                    결제된 상태의 집합은 util/orderStatus 가 정한다. */}
                {isPaid(order.orderStatus) ? (
                    <CheckBadgeIcon className="w-8 h-8 fill-green-500" />
                ) :  (
                    <XCircleIcon className="w-8 h-8 fill-red-500" />
                )}
            </div>
            <div className="mt-2 flex items-center">
                <span className="font-semibold mr-2">{t("order.status")}</span>
                <span className={statusTone(order.orderStatus)} data-testid="order-status">
                    {orderStatusLabel(t, order.orderStatus)}
                </span>
            </div>
        </div>
    );
};

export default OrderInfo;
