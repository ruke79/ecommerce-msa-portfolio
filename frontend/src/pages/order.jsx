import Payment from "components/order/Payment";
import Header from "pages/header/Header";
import OrderInfo from "components/order/OrderInfo";
import Product from "components/order/Product";
import Total from "components/order/Total";
import UserInfo from "components/order/UserInfo";
import React, { useEffect, useRef, useState } from "react";
import DotLoaderSpinner from "components/loader/Loading"
import { useLoaderData } from "react-router-dom";
import { useAuthContext } from "store/AuthContext";
import { useFetchOrder } from "hook/hooks";
import DeliveryTracker from "components/delivery/DeliveryTracker";
import StripeReturn from "components/order/StripeReturn";
import RefundButton from "components/order/RefundButton";
import ConfirmPurchaseButton from "components/order/ConfirmPurchaseButton";
import OrderProgress from "components/order/OrderProgress";
import { useOrderSettling } from "hook/useOrderSettling";
import { useDispatch, useSelector } from "react-redux";
import { CartStatus, emptyCart } from "../redux/CartSlice";
import { cartIsThisOrder } from "util/cartOrder";
import { isCanceled, isPaid, isRefunded, orderRank, rankForDeliveryStage } from "util/orderStatus";
import { useTranslation } from "i18n/I18nProvider";

const OrderPage = () => {
    const { t } = useTranslation();
    const orderId = useLoaderData();
        
    
    const { user } = useAuthContext();

    const { orderData, isPendingOrder, isSuccessOrder, isError, refetchOrder }  = useFetchOrder(orderId, user.email);

    // 결제 확인 뒤 주문이 확정(PAID)될 때까지 다시 읽는다. 확인 응답과 주문 상태 변경은
    // 다른 시점이다(사가) — 그 사이를 화면이 "완료" 라고 말하면 결제 상태 X 와 나란히
    // 놓여 모순으로 보인다. useOrderSettling 주석.
    const reduxDispatch = useDispatch();
    const { settling, timedOut, start: startSettling } = useOrderSettling(orderData, refetchOrder, {
        // 🐛 Stripe 로 결제하면 장바구니가 화면에 그대로 남았다. 서버 사본은 사가가 비우지만
        //    (publishCartEmptyState) 브라우저의 redux 사본(redux-persist, localStorage)은 PayPal
        //    경로만 비우고 있었다. 확정된 순간 — 결제수단과 무관하게 — 여기서 비운다.
        onSettled: (settled) => {
            if (settled?.orderStatus !== "NOT_PROCESSED" && settled?.orderStatus !== "CANCELED") {
                reduxDispatch(emptyCart());
            }
        },
    });

    // 🐛 결제는 됐는데 장바구니가 화면에 남았다 — 위 onSettled 는 결제수단에서 **돌아오는 흐름**(확인 → 확정 대기)이 돌 때만
    //    비운다. 결제 페이지에서 연결이 끊겨 돌아오지 못하고(Stripe "インターネット接続が不安定です") 주문 화면을 직접 열면
    //    그 흐름이 없다. 서버 사본은 사가가 비웠는데 브라우저 사본(redux-persist)만 남는다.
    //    결제된 주문을 볼 때, 장바구니가 체크아웃 뒤 손대지 않은 그대로(COMMITED)이고 담긴 것이 전부 이 주문에 있으면 비운다
    //    — 옛 주문을 보다가 지금 체크아웃 중인 새 장바구니를 지우지 않게 내용까지 대조한다.
    const cart = useSelector((state) => state.cart);
    useEffect(() => {
        if (orderData && isPaid(orderData.orderStatus) && cart?.status === CartStatus.COMMITED
                && cartIsThisOrder(cart.cartItems, orderData.products)) {
            reduxDispatch(emptyCart());
        }
    }, [orderData, cart, reduxDispatch]);

    // 🐛 배송을 취소해도 주문은 DISPATCHED 그대로였고, "배송이 시작된 주문은 환불할 수 없습니다"
    //    가 그대로 떠 있었다. 서버는 이제 취소를 받으면 주문을 PAID 로 되돌리는데, 그것도
    //    Kafka 왕복 뒤라 화면은 다시 읽어야 안다 — 결제 확정을 기다리던 것과 같은 모양이다.
    const { start: startCancelSettling } = useOrderSettling(orderData, refetchOrder, {
        until: (s) => s !== "PROCESSING" && s !== "DISPATCHED",
    });

    // 🐛 송장이 등록되면 배송 추적(폴링)은 "발송되었습니다" 로 바뀌는데 진행 띠·환불 안내는
    //    새로고침해야 바뀌었다. 주문은 react-query 가 들고 있고 아무도 다시 읽으라고 하지
    //    않았다. 배송 상태가 바뀐 것을 보면 주문이 그 단계를 따라잡을 때까지 다시 읽는다 —
    //    주문 상태는 같은 이벤트를 Kafka 로 받아 바뀌므로 몇 초 늦는다.
    const expectedRank = useRef(0);
    const { start: startFollowDelivery } = useOrderSettling(orderData, refetchOrder, {
        until: (s) => orderRank(s) >= expectedRank.current,
    });
    const onDeliveryStatusChange = (delivery) => {
        const rank = rankForDeliveryStage(delivery?.stage);
        if (rank == null) return;              // 취소·반송은 여기 규칙이 아니다(onCancelled)
        expectedRank.current = rank;
        startFollowDelivery();
    };

    // 결제 컴포넌트가 "결제됐다" 고 알리면 확정을 기다리고, 그 밖의 변화는 다시 읽는다.
    // (예전에는 여기 useState 가 있었고 아무도 읽지 않았다.)
    const onOrderChanged = (next) => {
        if (next?.orderStatus === "PAID") startSettling();
        else refetchOrder();
    };
    const [loading, setLoading] = useState(false);
       
    

    if (isPendingOrder ) {        
        return <DotLoaderSpinner loading={isPendingOrder} />
    }
           
    
    return (
        <React.Fragment>
      
            <Header title={t("order.detail.title")} />
            <main className="max-w-screen-2xl mx-auto bg-gray-100 grid grid-cols-3 md:px-10 pt-5 pb-8 gap-8">
                <section className="col-span-2 bg-white p-2 md:p-5 rounded-xl border">
                    <OrderProgress order={orderData} settling={settling} timedOut={timedOut} />
                    <OrderInfo order={orderData} />

                    {orderData.products.map((product,i) => (
                        <Product key={i} product={product} />
                    ))}

                    <Total order={orderData} />
                </section>
                <section className="md:col-span-1 h-fit bg-white p-2 md:p-5 rounded-xl border">
                    <UserInfo order={orderData} user={user} />

                    {/* Stripe 결제 후 돌아온 경우. 웹훅과 겹쳐도 서버 반영이 멱등하다
                        (StripeReturn 주석). 🐛 여기가 window.location.reload() 였다 — 주소의
                        ?stripe=success&session_id=… 가 남은 채 다시 떠서 확인 → 완료 →
                        새로고침이 무한히 반복됐다. 주문만 다시 읽는다. */}
                    <StripeReturn order={orderData} onPaid={startSettling} />
                    { (orderData.orderStatus === "NOT_PROCESSED" || orderData.orderStatus === "UNPAID" ) && !settling
                    && (<Payment order={orderData} setOrder={onOrderChanged} setLoading={setLoading} profile={false} />)}

                    {/* 결제된 주문의 환불. 상태에 따라 버튼·안내·완료 중 하나를 스스로 고른다. */}
                    {/* 환불 뒤에는 주문만 다시 읽는다. 장바구니는 건드리지 않는다 — 환불은 물건을
                        다시 담아 주는 것이 아니다. */}
                    <RefundButton order={orderData} onRefunded={() => refetchOrder()} />

                    {/* 배달 완료된 주문의 구매 확정 — 주문의 마지막 단계(docs/25 §12). 확정된
                        주문에는 시각을, 자동 확정이면 그렇다고 보여 준다. 상태는 컴포넌트가 고른다. */}
                    <ConfirmPurchaseButton order={orderData} onConfirmed={() => refetchOrder()} />

                    {/* 결제가 끝난 주문에는 배송 추적을 보여 준다. 결제 직후에는 아직
                        delivery 가 없을 수 있는데, 그건 오류가 아니라 "배송 준비 중"이고
                        DeliveryTracker 가 그 상태를 스스로 처리한다. */}
                    {/* 🐛 환불·취소된 주문에도 "배송 준비 중" 이 떠 있었다 — 배송할 것이 없다. */}
                    {orderData.orderStatus !== "NOT_PROCESSED"
                        && orderData.orderStatus !== "UNPAID"
                        && !isCanceled(orderData.orderStatus) && !isRefunded(orderData.orderStatus) && (
                        <div className="mt-4">
                            <DeliveryTracker
                                orderId={orderData.id ?? orderId}
                                orderStatus={orderData.orderStatus}
                                onCancelled={startCancelSettling}
                                onStatusChange={onDeliveryStatusChange}
                            />
                        </div>
                    )}
                </section>
            </main>
        </React.Fragment>
    );
};

export default OrderPage;


export const loader = async ({params, request}) => {
            
        const orderId = params.id;

        return orderId;     
    
}

