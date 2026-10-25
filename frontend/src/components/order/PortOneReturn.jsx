import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { postRequest } from "util/api";
import { PORTONE_RETURN_KEYS, portOneReturnOf } from "util/portone";
import { portOneFailureText } from "util/portoneError";
import { isCanceled, isPaid, isRefunded } from "util/orderStatus";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 포트원 V2 결제창이 <b>리디렉션</b>으로 끝나 이 주문 화면으로 돌아온 순간을 처리한다.
 *
 * <p>🐛 2026-10-24 운영: KG이니시스 채널에서 결제창이 "리디렉션 URL은 필수 입력입니다" 로 열리지 않았다 — 모바일 등에서는
 * 결제창이 페이지 이동으로 열리고 {@code requestPayment} 의 반환값이 오지 않는다. 결과는 주소의 쿼리로 온다:
 * {@code ?paymentId=…}(성공) · {@code &code=…&message=…}(실패, PG 문구는 {@code pgCode} · {@code pgMessage}).
 *
 * <p>확인은 반환값 방식과 같다 — 🔒 보내는 것은 결제 id 하나다. 서버가 포트원에 조회해 그 결제의 {@code customData.trackingId}
 * 가 이 주문인지 · 금액이 맞는지 대조한다(쿼리는 누구나 만들 수 있으니 믿지 않는다). 웹훅과 겹쳐도 서버 반영이 멱등하다.
 * 쿼리는 확인 결과를 알리기 <b>전에</b> 지운다(StripeReturn 의 무한 새로고침과 같은 이유).
 *
 * <p>[JA] ポートワン V2 決済画面がリダイレクトで終わり、この注文画面へ戻った瞬間を処理する。結果はクエリで来る(成功 paymentId ·
 * 失敗 code/message)。確認は戻り値方式と同じく決済 id 一つだけを送り、サーバーがポートワンに照会して注文 · 金額を照合する。
 * クエリは結果を知らせる前に消す。
 */
const PortOneReturn = ({ order, onConfirmed }) => {
    const { t, lang } = useTranslation();
    const [searchParams, setSearchParams] = useSearchParams();
    const [state, setState] = useState("idle");   // idle | confirming | done | failed | declined
    const [message, setMessage] = useState("");
    const started = useRef(false);

    const returned = portOneReturnOf(searchParams);

    useEffect(() => {
        if (!returned || started.current || !order?.trackingId) {
            return;
        }
        started.current = true;

        const clearQuery = () => {
            PORTONE_RETURN_KEYS.forEach((k) => searchParams.delete(k));
            setSearchParams(searchParams, { replace: true });
        };

        // 실패(사용자 취소 포함) — 확인할 것이 없다. PG 문구는 화면 언어로(util/portoneError).
        if (returned.code !== undefined) {
            setMessage(portOneFailureText(t, lang, returned));
            setState("declined");
            clearQuery();
            return;
        }

        setState("confirming");
        postRequest("/order-service/api/order/payment/portone/confirm", {
            trackingId: order.trackingId,
            providerPaymentId: returned.paymentId,
        })
            .then((res) => {
                clearQuery();
                // 가상계좌는 발급만 됐다 — UNPAID(입금 대기). 입금 안내는 주문을 다시 읽으면 결제 칸이 보여 준다.
                const status = res?.data?.orderStatus === "UNPAID" ? "UNPAID" : "PAID";
                setState(status === "PAID" ? "done" : "idle");
                onConfirmed?.({ orderStatus: status });
            })
            .catch((error) => {
                // 실패해도 결제가 사라진 것은 아니다 — 웹훅이 뒤늦게 반영할 수 있다.
                console.error("PortOne redirect confirm error: ", error);
                clearQuery();
                setState("failed");
                setMessage(error?.response?.data?.message ?? "");
            });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [returned?.paymentId, order?.trackingId]);

    if (isPaid(order?.orderStatus) || isCanceled(order?.orderStatus) || isRefunded(order?.orderStatus)) {
        return null;
    }

    if (state === "declined") {
        return (
            <p className="my-2 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-900" role="alert">
                {message}
            </p>
        );
    }

    if (state === "confirming") {
        return <p className="my-2 text-sm text-slate-600">{t("stripe_return.confirming")}</p>;
    }

    if (state === "done") {
        return (
            <p className="my-2 rounded border border-green-300 bg-green-50 p-3 text-sm text-green-900">
                {t("stripe_return.confirmed")}
            </p>
        );
    }

    if (state === "failed") {
        return (
            <p className="my-2 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900" role="alert">
                {t("payment.confirm_delayed")}
                {message && <span className="mt-1 block text-xs">{message}</span>}
            </p>
        );
    }

    return null;
};

export default PortOneReturn;
