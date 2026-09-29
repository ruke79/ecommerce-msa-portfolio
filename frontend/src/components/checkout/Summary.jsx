import { useCallback, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";
import { useNavigate } from "react-router-dom";
import { applyCoupon, postRequest } from "util/api";
import { roundMoney, splitCartTotal } from "util/pricing";
import { useMoney } from "hook/useMoney";
import { useFetchCoupons } from "hook/hooks";
import CouponPicker from "./CouponPicker";



const Summary = ({    
    selectedAddress,
    user,
    cart,
    paymentMethod,
    totalAfterDiscount,
    setTotalAfterDiscount,
    setLoading
}) => {
    const { t } = useTranslation();
    // 상점 통화로 — 예전에는 `{formatMoney(x)}$` 로 기호를 손으로 붙였다.
    const money = useMoney();
    const [coupon, setCoupon] = useState("");
    const [discount, setDiscount] = useState("");
    const [applied, setApplied] = useState(null);
    const [applying, setApplying] = useState(false);
    const [error, setError] = useState("");
    const [order_error, setOrder_Error] = useState("");

    const navigate = useNavigate();

    const { coupons, isSuccess, isPending } = useFetchCoupons(user.email);

    // ⚠️ 이 아래는 전부 훅이다. 조기 return(`if (isPending) return ...`)은 훅을 전부 부른
    // **뒤에** 와야 한다. 훅보다 앞에 두면 렌더마다 호출되는 훅의 개수가 달라져
    // "Rendered fewer hooks than expected" 로 컴포넌트가 깨진다.

    /**
     * 고른 쿠폰을 적용한다.
     *
     * <p>예전에는 쿠폰 목록을 받는 즉시 <b>첫 번째 쿠폰을 자동으로 적용</b>했다. 쿠폰이
     * 한 장뿐이던 시절의 타협이었는데, 제약(카테고리·상품) 있는 쿠폰이 생기면서
     * "장바구니에 맞지 않는 쿠폰이 저절로 적용되고 할인은 0원"이 가능해졌다. 고른 것만
     * 적용한다.
     */
    const applyCouponHandler = useCallback(async (couponName) => {
        const code = couponName ?? coupon;

        if (!code) {
            // "쿠폰 사용 안 함" — 서버를 부르지 않고 되돌린다.
            setApplied(null);
            setDiscount("");
            setError("");
            setTotalAfterDiscount("");
            return;
        }

        setApplying(true);
        try {
            // 이메일은 보내지 않는다 — 신원은 토큰이 정한다(util/api.js applyCoupon 주석).
            const result = await applyCoupon(cart.cartTotal, code);

            // 서버가 message 를 담아 주면 "이 쿠폰은 지금 쓸 수 없다"는 뜻이다
            // (만료됐거나, 대상 상품이 장바구니에 없거나).
            if (result?.message) {
                setError(result.message);
                setApplied(null);
                setDiscount("");
                setTotalAfterDiscount("");
            } else {
                setTotalAfterDiscount(result.totalAfterDiscount);
                setDiscount(result.discount);
                setApplied(result);
                setError("");
            }
        } catch (e) {
            // util/api 가 더 이상 오류를 삼키지 않으므로 여기서 받는다. 쿠폰 적용 실패로
            // 결제 화면 전체가 ErrorBoundary 로 날아가면 안 된다.
            setError(e?.response?.data?.message ?? t("checkout.coupon.apply_failed"));
            setApplied(null);
            setDiscount("");
            setTotalAfterDiscount("");
        } finally {
            setApplying(false);
        }
    }, [coupon, cart.cartTotal, setTotalAfterDiscount, t]);

    const selectCouponHandler = useCallback((couponName) => {
        setCoupon(couponName);
        applyCouponHandler(couponName);
    }, [applyCouponHandler]);

    // 총액을 상품 금액과 배송비로 나눈다. 쿠폰은 배송비를 깎지 않으므로 배송비는
    // 할인 전후로 같다 — 서버의 CouponCalculator 와 같은 규칙이다.
    const amounts = splitCartTotal(cart);

    const [placing, setPlacing] = useState(false);

    const placeOrderHandler = async () => {
        // 중복 제출 방지. 예전에는 아무 가드가 없어서 주문 버튼을 두 번 누르면
        // 주문이 두 건 생성됐다 — 결제까지 두 번 이어질 수 있는 경로다.
        if (placing) return;

        // `=== ""` 였다. 초기값이 undefined 라(checkout.jsx 주석) 이 가드를 그대로
        // 통과해 결제수단 없는 주문이 만들어졌다. 비어 있음은 전부 막는다.
        if (!paymentMethod) {
            setOrder_Error(t("checkout.error.no_payment"));
            return;
        }
        if (!selectedAddress) {
            setOrder_Error(t("checkout.error.no_address"));
            return;
        }

        setPlacing(true);
        setLoading(true);
        setOrder_Error("");
        try {
            // 🔒 금액을 보내지 않는다. 서버가 장바구니와 쿠폰으로 직접 계산한다(docs/40 #10).
            //
            // 예전에는 여기서 total 과 totalBeforeDiscount 를 보냈고 서버는 그것을 그대로
            // 저장했다. PayPal 검증은 "결제액 == DB 의 주문 총액" 만 보므로, DevTools 로
            // total 을 1로 바꾸면 1달러 결제가 정상적으로 검증을 통과했다.
            //
            // 지금은 서버의 OrderRequest 에 금액 필드 자체가 없다 — 보내도 버려진다.
            const { data } = await postRequest("/order-service/api/order/create", {
                shippingAddress: selectedAddress,
                paymentMethod,
                couponApplied: coupon,
                // 신원(email)은 보내지 않는다 — 주문자는 게이트웨이가 검증한 토큰의 uid 다.
            });

            navigate(`/order/${data.orderId}`);
        } catch (error) {
            setOrder_Error(
                error?.response?.data?.message ?? t("checkout.error.create_failed")
            );
        } finally {
            // 예전에는 setLoading(false) 가 두 군데 다 주석 처리돼 있었다. 그래서 주문
            // 생성이 실패하면 전체 화면 스피너가 **영원히** 떠 있었고, 사용자는 오류
            // 메시지조차 볼 수 없어 새로고침 말고는 할 수 있는 게 없었다.
            setLoading(false);
            setPlacing(false);
        }
    };

    // 훅을 모두 호출한 뒤에 조기 return 한다.
    if (isPending) return <div><p>{t("checkout.loading")}</p></div>;


   
    return (
        <div className="mt-3">
            <h3 className=" pb-2 mb-4 border-b border-b-2  text-xl font-semibold">
                {t("checkout.summary.title")}
            </h3>
            <div>
                <CouponPicker
                    coupons={coupons}
                    selected={coupon}
                    onSelect={selectCouponHandler}
                    disabled={applying}
                />

                {error && (
                    <p className="mx-3 my-2 text-red-500 text-sm">{error}</p>
                )}
                {applying && (
                    <p className="mx-3 my-2 text-sm text-slate-500">{t("checkout.coupon.applying")}</p>
                )}

                {/*
                  * 🔴 예전에는 "Total" 한 줄이 전부였다. 그 안에 배송비가 녹아 있어서
                  *    사용자는 **자기가 배송비를 얼마 내는지 알 수 없었다.** 서버가 이제
                  *    항목마다 배송비를 실어 보내므로 나눠서 보여 준다(util/pricing).
                  */}
                <dl className="mx-3 flex flex-col gap-1 text-sm">
                    <div className="flex justify-between">
                        <dt>{t("checkout.merchandise")}</dt>
                        <dd>{money(roundMoney(amounts.merchandise))}</dd>
                    </div>
                    <div className="flex justify-between">
                        <dt>{t("checkout.shipping")}</dt>
                        {/* 0원을 "무료"로 바꿔 말하지 않는다 — 실제로 0원인지 아직
                            계산되지 않은 것인지 구분되지 않는다. */}
                        <dd>{money(roundMoney(amounts.shipping))}</dd>
                    </div>
                    <div className="flex justify-between border-t pt-1 font-semibold">
                        <dt>{t("checkout.total")}</dt>
                        <dd>{money(roundMoney(amounts.total))}</dd>
                    </div>
                </dl>

                <div className="mx-3 flex flex-col">

                    {discount !== "" && (
                        <span className="bg-green-600 p-2 my-2 text-white rounded-xl">
                            {t("checkout.coupon.applied")} <b>-{discount}%</b>
                            {/*
                              * 할인율만으로는 얼마가 깎였는지 알 수 없다 — 제약 있는 쿠폰은
                              * 장바구니 전체가 아니라 대상 상품 소계에만 적용된다.
                              * "10% 쿠폰인데 왜 1,000원만 깎였지?"에 답하는 줄이다.
                              */}
                            {applied?.discountAmount != null && (
                                <span className="block text-xs mt-1">
                                    {applied.eligibleSubtotal != null
                                        && Number(applied.eligibleSubtotal) < Number(cart.cartTotal)
                                        ? t("checkout.coupon.scoped", {
                                              count: applied.eligibleItemCount,
                                              subtotal: money(roundMoney(applied.eligibleSubtotal)),
                                              amount: money(roundMoney(applied.discountAmount)),
                                          })
                                        : t("checkout.coupon.amount", { amount: money(roundMoney(applied.discountAmount)) })}
                                </span>
                            )}
                        </span>
                    )}

                    {totalAfterDiscount !== "" && totalAfterDiscount < cart.cartTotal && (
                        <span>
                            {t("checkout.coupon.new_price")} <b>{money(roundMoney(totalAfterDiscount))}</b>
                        </span>
                    )}
                </div>
            </div>
            <button
                type="button"
                disabled={placing}
                className={`mx-3 cursor-pointer text-amazon-blue_dark font-semibold bg-gradient-to-r from-amazon-orange to-yellow-300 text-amazon-blue_dark  hover:text-slate-100 hover:from-amazon-blue_light hover:to-slate-400 w-full my-6 p-4 rounded-full transition duration-300 disabled:opacity-50 disabled:cursor-not-allowed`}
                onClick={() => placeOrderHandler()}
            >
                {placing ? t("checkout.placing") : t("checkout.place_order")}
            </button>
            {order_error && (
                <span className="mx-3 text-red-500 mt-2">{order_error}</span>
            )}
        </div>
    );
};

export default Summary;
