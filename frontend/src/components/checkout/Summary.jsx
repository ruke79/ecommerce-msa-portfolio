import { useCallback, useRef, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";
import { useNavigate } from "react-router-dom";
import { applyCoupon, postRequest } from "util/api";
import { roundMoney, splitCartTotal } from "util/pricing";
import { useMoney } from "hook/useMoney";
import { useFetchCoupons } from "hook/hooks";
import CouponPicker from "./CouponPicker";

/** 주문 생성 멱등 키 — 서버는 8 ~ 64자의 영숫자 · '-' · '_' 만 쓴다(OrderService.usableCreateKey). */
// [JA] 注文作成の冪等キー — サーバーは 8 ~ 64 文字の英数字 · '-' · '_' だけを使う(OrderService.usableCreateKey)。
function newOrderCreateKey() {
    return typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}



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
    // [JA] ストア通貨で — 以前は `{formatMoney(x)}$` で記号を手で付けていた。
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
    // [JA] ⚠️ この下はすべてフックである。早期 return(`if (isPending) return ...`)はフックをすべて呼んだ
    //      **後に** 来なければならない。フックより前に置くとレンダーごとに呼ばれるフックの数が変わり、
    //      "Rendered fewer hooks than expected" でコンポーネントが壊れる。

    /**
     * 고른 쿠폰을 적용한다.
     *
     * <p>예전에는 쿠폰 목록을 받는 즉시 <b>첫 번째 쿠폰을 자동으로 적용</b>했다. 쿠폰이
     * 한 장뿐이던 시절의 타협이었는데, 제약(카테고리·상품) 있는 쿠폰이 생기면서
     * "장바구니에 맞지 않는 쿠폰이 저절로 적용되고 할인은 0원"이 가능해졌다. 고른 것만
     * 적용한다.
     *
     * <p>[JA] 選んだクーポンを適用する。
     * 以前はクーポン一覧を受け取るとすぐ最初のクーポンを自動で適用していた。クーポンが一枚だけだった頃の妥協だったが、
     * 制約(カテゴリー · 商品)付きクーポンができて「カートに合わないクーポンが勝手に適用されて割引は 0 円」がありうるようになった。
     * 選んだものだけ適用する。
     */
    const applyCouponHandler = useCallback(async (couponName) => {
        const code = couponName ?? coupon;

        if (!code) {
            // "쿠폰 사용 안 함" — 서버를 부르지 않고 되돌린다.
            // [JA] 「クーポンを使用しない」 — サーバーを呼ばずに戻す。
            setApplied(null);
            setDiscount("");
            setError("");
            setTotalAfterDiscount("");
            return;
        }

        setApplying(true);
        try {
            // 이메일은 보내지 않는다 — 신원은 토큰이 정한다(util/api.js applyCoupon 주석).
            // [JA] メールは送らない — 身元はトークンが決める(util/api.js applyCoupon のコメント)。
            const result = await applyCoupon(cart.cartTotal, code);

            // 서버가 message 를 담아 주면 "이 쿠폰은 지금 쓸 수 없다"는 뜻이다
            // (만료됐거나, 대상 상품이 장바구니에 없거나).
            // [JA] サーバーが message を入れて返すと「このクーポンは今使えない」という意味である
            //      (期限切れか、対象商品がカートにない)。
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
            // [JA] util/api がもうエラーを握りつぶさないのでここで受ける。クーポン適用の失敗で
            //      決済画面全体が ErrorBoundary へ飛んではならない。
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
    // [JA] 総額を商品金額と送料に分ける。クーポンは送料を割り引かないので送料は
    //      割引前後で同じである — サーバーの CouponCalculator と同じ規則である。
    const amounts = splitCartTotal(cart);

    const [placing, setPlacing] = useState(false);

    // 주문 시도 하나에 멱등 키 하나(Idempotency-Key). 응답이 끊겨 다시 누르면 같은 키로 가서 서버가 처음 만든 주문을 돌려준다 —
    // 예전에는 요청이 서버에 닿고 응답만 잃으면 다시 누를 때 주문이 하나 더 생겼다(placing 가드는 같은 화면의 두 번 누름만 막는다).
    // 배송지 · 결제수단 · 쿠폰을 바꾸면 다른 주문이므로 새 키, 주문이 만들어지면 비운다.
    // [JA] 注文の試行ごとに冪等キーを一つ。応答が途切れて押し直すと同じキーで送り、サーバーは最初の注文を返す。
    // 配送先・決済手段・クーポンを変えたら新しいキー、注文ができたら消す。
    const attemptRef = useRef(null);
    const attemptKey = () => {
        const signature = JSON.stringify([selectedAddress, paymentMethod, coupon]);
        if (!attemptRef.current || attemptRef.current.signature !== signature) {
            attemptRef.current = { signature, key: newOrderCreateKey() };
        }
        return attemptRef.current.key;
    };

    const placeOrderHandler = async () => {
        // 중복 제출 방지. 예전에는 아무 가드가 없어서 주문 버튼을 두 번 누르면
        // 주문이 두 건 생성됐다 — 결제까지 두 번 이어질 수 있는 경로다.
        // [JA] 二重送信防止。以前は何のガードもなく注文ボタンを二度押すと
        //      注文が二件作られた — 決済まで二度つながりうる経路である。
        if (placing) return;

        // `=== ""` 였다. 초기값이 undefined 라(checkout.jsx 주석) 이 가드를 그대로
        // 통과해 결제수단 없는 주문이 만들어졌다. 비어 있음은 전부 막는다.
        // [JA] `=== ""` だった。初期値が undefined なので(checkout.jsx のコメント)このガードをそのまま
        //      通過し、決済手段のない注文が作られた。空はすべて止める。
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
            // [JA] 🔒 金額を送らない。サーバーがカートとクーポンで直接計算する(docs/40 #10)。
            //      以前はここで total と totalBeforeDiscount を送り、サーバーはそれをそのまま保存した。
            //      PayPal の検証は「決済額 == DB の注文総額」だけを見るので、DevTools で total を 1 にすると
            //      1 ドルの決済が正常に検証を通過した。
            //      今はサーバーの OrderRequest に金額フィールド自体がない — 送っても捨てられる。
            const { data } = await postRequest("/order-service/api/order/create", {
                shippingAddress: selectedAddress,
                paymentMethod,
                couponApplied: coupon,
                // 신원(email)은 보내지 않는다 — 주문자는 게이트웨이가 검증한 토큰의 uid 다.
                // [JA] 身元(email)は送らない — 注文者はゲートウェイが検証したトークンの uid である。
            }, "application/json", { headers: { "Idempotency-Key": attemptKey() } });

            attemptRef.current = null;
            navigate(`/order/${data.orderId}`);
        } catch (error) {
            setOrder_Error(
                error?.response?.data?.message ?? t("checkout.error.create_failed")
            );
        } finally {
            // 예전에는 setLoading(false) 가 두 군데 다 주석 처리돼 있었다. 그래서 주문
            // 생성이 실패하면 전체 화면 스피너가 **영원히** 떠 있었고, 사용자는 오류
            // 메시지조차 볼 수 없어 새로고침 말고는 할 수 있는 게 없었다.
            // [JA] 以前は setLoading(false) が二か所ともコメントアウトされていた。そのため注文作成が
            //      失敗すると全画面スピナーが **永遠に** 表示され、ユーザーはエラー
            //      メッセージさえ見られず再読み込み以外にできることがなかった。
            setLoading(false);
            setPlacing(false);
        }
    };

    // 훅을 모두 호출한 뒤에 조기 return 한다.
    // [JA] フックをすべて呼んだ後に早期 return する。
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
                {/* [JA] 🔴 以前は "Total" 一行がすべてだった。その中に送料が溶け込んでいて、
                    ユーザーは **自分が送料をいくら払うのか分からなかった。** サーバーが今は
                    項目ごとに送料を載せて送るので分けて見せる(util/pricing)。 */}
                <dl className="mx-3 flex flex-col gap-1 text-sm">
                    <div className="flex justify-between">
                        <dt>{t("checkout.merchandise")}</dt>
                        <dd>{money(roundMoney(amounts.merchandise))}</dd>
                    </div>
                    <div className="flex justify-between">
                        <dt>{t("checkout.shipping")}</dt>
                        {/* 0원을 "무료"로 바꿔 말하지 않는다 — 실제로 0원인지 아직
                            계산되지 않은 것인지 구분되지 않는다. */}
                        {/* [JA] 0 円を「無料」と言い換えない — 本当に 0 円なのかまだ
                            計算されていないのか区別がつかない。 */}
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
                            {/* [JA] 割引率だけではいくら引かれたか分からない — 制約付きクーポンは
                                カート全体ではなく対象商品の小計にだけ適用される。
                                「10% クーポンなのになぜ 1,000 ウォンしか引かれないの?」に答える行である。 */}
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
