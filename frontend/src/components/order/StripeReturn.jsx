import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { postRequest } from "util/api";
import { isCanceled, isPaid, isRefunded } from "util/orderStatus";
import { useTranslation } from "i18n/I18nProvider";

/**
 * Stripe 결제 후 <b>브라우저가 돌아온 순간</b>을 처리한다.
 *
 * <h3>왜 화면에서 한 번 더 확인하는가 — 웹훅이 있는데</h3>
 *
 * <p>확정 경로가 둘이고 <b>둘 다 필요하다</b>:
 *
 * <ul>
 *   <li><b>웹훅</b>이 권위 있는 경로다. 브라우저가 돌아오지 않아도(결제 직후 탭을 닫아도)
 *       결제가 반영된다 — 그게 없으면 <b>돈은 빠졌는데 주문은 미결제</b>로 남는다.</li>
 *   <li><b>복귀 검증</b>은 사용자를 위한 것이다. 웹훅만 두면 결제 직후 화면이 "확인 중"에
 *       머물고, 사용자는 결제가 됐는지 알 수 없어 다시 결제한다.</li>
 * </ul>
 *
 * <p>둘이 <b>겹쳐서 도착하는 것이 정상</b>이다. 서버의 반영은 멱등하므로 어느 쪽이 먼저
 * 와도 결과가 같다.
 *
 * <p>🔒 보내는 것은 <b>세션 id 하나</b>다. 금액도 결제 상태도 보내지 않는다 — 서버가 그
 * 세션을 Stripe 에 직접 조회해 금액까지 대조한다(docs/35).
 */
const StripeReturn = ({ order, onPaid }) => {
    const { t } = useTranslation();
    const [searchParams, setSearchParams] = useSearchParams();
    const [state, setState] = useState("idle");   // idle | confirming | done | failed
    const [message, setMessage] = useState("");

    // React 18 의 StrictMode 는 개발 중 effect 를 두 번 실행한다. 서버가 멱등하므로
    // 결과는 같지만, 불필요한 요청과 "확인 중" 깜빡임을 막는다.
    const started = useRef(false);

    const result = searchParams.get("stripe");
    const sessionId = searchParams.get("session_id");

    useEffect(() => {
        if (result !== "success" || !sessionId || started.current) {
            return;
        }
        started.current = true;
        setState("confirming");

        // 쿼리를 지운다. 남겨 두면 새로고침·뒤로가기마다 다시 확인 요청이 나가고,
        // 무엇보다 세션 id 가 주소창과 방문 기록에 계속 남는다.
        //
        // 🐛 <b>반드시 onPaid 보다 먼저.</b> 예전에는 finally 에서 지웠는데, then 의 onPaid 가
        //    window.location.reload() 였다 — reload 는 "그 순간의 주소"로 다시 뜨므로
        //    ?stripe=success&session_id=… 가 그대로 남은 채 다시 떴고, 다시 확인하고, 다시
        //    reload… "확인 중 → 완료 → 새로고침" 이 무한히 반복됐다. 서버는 멱등이라 돈은
        //    한 번만 나갔지만 화면은 영원히 돌았다.
        const clearQuery = () => {
            searchParams.delete("stripe");
            searchParams.delete("session_id");
            setSearchParams(searchParams, { replace: true });
        };

        postRequest("/order-service/api/order/payment/stripe/confirm", {
            trackingId: order.trackingId,
            providerPaymentId: sessionId,
        })
            .then(() => {
                clearQuery();
                setState("done");
                onPaid?.();
            })
            .catch((error) => {
                // 실패해도 결제가 사라진 것은 아니다 — 웹훅이 뒤늦게 반영할 수 있다.
                // 그래서 "실패했다"가 아니라 "확인 중"이라고 말한다.
                console.error("Stripe confirm error: ", error);
                clearQuery();
                setState("failed");
                setMessage(error?.response?.data?.message ?? "");
            });
    }, [result, sessionId, order.trackingId, onPaid, searchParams, setSearchParams]);

    // 주문이 확정된 뒤(또는 취소·환불된 뒤)에는 이 띠가 할 말이 없다 — 진행은 OrderProgress 가
    // 보여 준다. 🐛 새로고침 없이 주문만 다시 읽게 되자 "결제가 확인됐습니다" 가 환불 뒤에도
    // 그대로 남아 있었다.
    if (isPaid(order?.orderStatus) || isCanceled(order?.orderStatus) || isRefunded(order?.orderStatus)) {
        return null;
    }

    if (result === "canceled") {
        return (
            <p className="my-2 rounded border border-slate-300 bg-slate-50 p-3 text-sm">
                {t("stripe_return.canceled")}
            </p>
        );
    }

    if (state === "confirming") {
        return <p className="my-2 text-sm text-slate-600">{t("stripe_return.confirming")}</p>;
    }

    if (state === "done") {
        return (
            <p className="my-2 rounded border border-green-300 bg-green-50 p-3 text-sm text-green-900">
                {/* "완료" 가 아니라 "확인" 이다 — 주문 확정은 사가가 곧 한다(useOrderSettling).
                    그 진행은 OrderProgress 가 보여 준다. */}
                {t("stripe_return.confirmed")}
            </p>
        );
    }

    if (state === "failed") {
        return (
            <p className="my-2 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
               role="alert">
                {t("payment.confirm_delayed")}
                {message && <span className="mt-1 block text-xs">{message}</span>}
            </p>
        );
    }

    return null;
};

export default StripeReturn;
