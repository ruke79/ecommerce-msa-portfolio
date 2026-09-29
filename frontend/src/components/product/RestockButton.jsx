import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { BellAlertIcon } from "@heroicons/react/24/outline";

import { useTranslation } from "i18n/I18nProvider";
import { fetchRestockSubscriptions, subscribeRestock, unsubscribeRestock } from "api/restockApi";
import { useAuthContext } from "../../store/AuthContext";

/** 색상 id 비교 — 서버는 색상 없는 상품을 null 로 돌려주고, 화면의 id 는 문자열일 수 있다. */
export const sameColor = (a, b) =>
    (a === null || a === undefined || a === "" ? null : String(a))
    === (b === null || b === undefined || b === "" ? null : String(b));

/**
 * 품절 옵션의 "재입고 알림 받기" (docs/53).
 *
 * <p>선택한 사이즈가 품절일 때만 보인다. 누르면 notification-service 에 신청을 걸고, 다시 누르면 거둔다. 재고가
 * 0 에서 다시 생기면(환불·반품 입고·운영자가 채움) catalog 가 알리고 알림이 온다 — 한 번 알린 신청은 끝난다.
 * 로그인하지 않았으면 로그인으로 보낸다(신원은 토큰에서만 온다).
 */
const RestockButton = ({ product, colorId, size, soldOut }) => {
    const { token } = useAuthContext();
    const { t } = useTranslation();
    const navigate = useNavigate();
    const queryClient = useQueryClient();
    const key = ["restock-subscriptions", product?.id];

    const { data: subscriptions = [] } = useQuery({
        queryKey: key,
        queryFn: () => fetchRestockSubscriptions(product.id),
        enabled: Boolean(token && soldOut && product?.id),
    });
    const subscribed = subscriptions.some((s) => s.size === size && sameColor(s.colorId, colorId));

    const toggle = useMutation({
        mutationFn: () => (subscribed
            ? unsubscribeRestock({ productId: product.id, colorId, size })
            : subscribeRestock({ productId: product.id, colorId, size,
                productName: product.name, productSlug: product.slug })),
        onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
    });

    if (!soldOut || !size) {
        return null;
    }

    const onClick = () => {
        if (!token) {
            navigate("/signin");
            return;
        }
        toggle.mutate();
    };

    return (
        <div className="mt-3 rounded border border-amber-200 bg-amber-50 p-3">
            <p className="text-sm text-amber-900">{token ? t("product.restock.hint") : t("product.restock.login")}</p>
            <button
                type="button"
                onClick={onClick}
                disabled={toggle.isPending}
                aria-pressed={subscribed}
                className={`mt-2 flex items-center space-x-2 rounded-full px-4 py-2 text-sm font-semibold transition disabled:opacity-50 ${
                    subscribed
                        ? "border border-amber-400 bg-white text-amber-800 hover:bg-amber-100"
                        : "bg-amber-500 text-white hover:bg-amber-600"
                }`}
            >
                <BellAlertIcon className="h-5 w-5" />
                <span>{subscribed ? t("product.restock.subscribed") : t("product.restock.subscribe")}</span>
            </button>
            {toggle.isError && (
                <p className="mt-2 text-sm text-red-600" role="alert">
                    {toggle.error?.response?.data?.message ?? t("product.restock.error")}
                </p>
            )}
        </div>
    );
};

export default RestockButton;
