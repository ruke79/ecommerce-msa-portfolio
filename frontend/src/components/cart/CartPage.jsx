import { useEffect, useState } from "react";
import CartHeader from "./CartHeader";
import Checkout from "./Checkout";
import PaymentMethods from "./PaymentMethods";
import Product from "./Product";
import api, { saveCart, putRequest, getRequest } from "util/api";
import { useLoaderData, useNavigate, useRouteLoaderData } from "react-router-dom";
import { useDispatch } from "react-redux";
import { commitCart, emptyCart, syncStock, updateCart } from "../../redux/CartSlice";
import { getCartStock } from "util/api";
import { cartLineStockOf, cartStockKey } from "util/stock";
import { useTranslation } from "i18n/I18nProvider";
import DotLoaderSpinner from "components/loader/Loading";
import { useAuthContext } from "store/AuthContext";
import { useQuery } from "@tanstack/react-query";
import tokenUtil from "util/tokenUtil";
import { roundMoney } from "util/pricing";


const CartPage = ({ cart }) => {
    const { token } = useAuthContext();
    const dispatch = useDispatch();

    const [selected, setSelected] = useState([]);
    const [shippingFee, setShippingFee] = useState(0);
    const [subTotal, setSubTotal] = useState(0);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);
    const [prevSelcted, setPrevSelectged] = useState(null);

    const navigate = useNavigate();
    const { t } = useTranslation();

    /**
     * 담아 둔 항목의 **지금** 재고(docs/59 §5). 장바구니의 수량은 담을 때 받은 값이라 며칠 전 것일 수 있다 — 화면에 들어올
     * 때, 그리고 다른 탭에서 돌아올 때 새로 본다. 실패하면(503 등) 가진 값을 그대로 둔다 — 조용히.
     *
     * <p>[JA] 入れておいた項目の **今の** 在庫(docs/59 §5)。カートの数量は入れたときに受けた値なので数日前のものかもしれない —
     * 画面に入るとき、そして別タブから戻ったときに改めて見る。失敗したら(503 など)持っている値をそのままにする — 静かに。
     */
    const stockKeys = [...new Set(cart.cartItems.map(cartStockKey).filter(Boolean))].sort();
    const { data: stock } = useQuery({
        queryKey: ["cart-stock", stockKeys],
        queryFn: () => getCartStock(stockKeys),
        enabled: stockKeys.length > 0,
        refetchOnWindowFocus: true,
        staleTime: 15_000,
        retry: false,
    });
    useEffect(() => {
        if (stock) {
            dispatch(syncStock({ stock, asked: stockKeys }));
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [stock, dispatch]);

    // `enable` 상태와 그것만 갱신하던 useEffect 를 지웠다. 어디에서도 읽지 않는 값이었고,
    // 의존성이 [] 라 장바구니가 바뀌어도 갱신되지 않아 읽었더라도 틀린 값이었다.
    // [JA] `enable` 状態とそれだけを更新していた useEffect を消した。どこからも読まれない値で、
    //      依存が [] なのでカートが変わっても更新されず、読んだとしても誤った値だった。

    // 금액은 **숫자**로 넘긴다(센트에서 고정). 예전에는 toFixed 의 문자열이었고, 그래서 Checkout 의
    // `shippingFee === 0` 이 늘 거짓이라 무료 배송이 "배송비 0.00$" 로 보였다. 통화 표기는 Checkout 이 한다.
    // [JA] 金額は **数値** で渡す(セントで固定)。以前は toFixed の文字列で、そのため Checkout の
    //      `shippingFee === 0` が常に偽で送料無料が「送料 0.00$」と見えた。通貨表記は Checkout が行う。
    useEffect(() => {
        const shipping = roundMoney(selected.reduce((sum, p) => sum + (Number(p.shipping) || 0), 0));
        const subtotal = roundMoney(selected.reduce((sum, p) => sum + Number(p.price) * p.qty, 0));
        setShippingFee(shipping);
        setSubTotal(subtotal);
        setTotal(roundMoney(subtotal + shipping));
    }, [selected]);



    const [saveError, setSaveError] = useState("");
    const [saving, setSaving] = useState(false);

    /**
     * 장바구니를 서버에 저장하고 결제 화면으로 넘어간다.
     *
     * <p>예전에는 이동이 `finally` 안에 있었다. finally 는 <b>성공이든 실패든</b> 실행되므로,
     * 저장이 실패해도 결제 화면으로 넘어갔다. 그 화면은 서버에 저장된 장바구니를 다시 읽어
     * 그리기 때문에, 사용자는 방금 담은 물건이 사라졌거나 예전 장바구니가 뜨는 상태에서
     * 결제를 진행하게 된다. 이동은 저장이 성공했을 때만 한다.
     *
     * <p>[JA] カートをサーバーに保存して決済画面へ進む。
     * 以前は遷移が `finally` の中にあった。finally は成功でも失敗でも実行されるので、保存が失敗しても決済画面へ進んだ。
     * その画面はサーバーに保存されたカートを読み直して描くため、ユーザーは今入れた品物が消えたり古いカートが出たりした状態で
     * 決済を進めることになる。遷移は保存が成功したときだけ行う。
     */
    const saveCartToDbHandler = async (e) => {
        e.preventDefault();
        if (saving) return;   // 중복 제출 방지 — 두 번 누르면 장바구니가 두 번 커밋된다 ／ [JA] 二重送信防止 — 二度押すとカートが二度コミットされる

        // 고른 항목 중 지금 재고로 살 수 없는 것이 있으면 넘어가지 않는다 — 주문 단계의 예약이 어차피 거절한다. 선택 목록은
        // 고를 때의 복사본이라 재고는 장바구니의 지금 항목에서 읽는다.
        // [JA] 選んだ項目のうち今の在庫で買えないものがあれば進まない — 注文段階の予約がどうせ拒否する。選択リストは
        //      選んだときのコピーなので、在庫はカートの今の項目から読む。
        const current = new Map(cart.cartItems.map((i) => [i._uid, i]));
        const blocked = selected.map((p) => current.get(p._uid) ?? p).filter((p) => cartLineStockOf(p));
        if (blocked.length > 0) {
            setSaveError(t("cart.stock.blocked", { names: blocked.map((p) => p.name).join(", ") }));
            return;
        }

        setSaving(true);
        setSaveError("");
        try {
            const res = await saveCart(selected);
            dispatch(commitCart(res.cartId));
            setPrevSelectged(selected);
            navigate("/checkout");
        } catch (err) {
            setSaveError(
                err?.response?.data?.message ?? t("cart.page.save_failed")
            );
        } finally {
            setSaving(false);
        }
    };

    return (
        <>
            {loading && <DotLoaderSpinner loading={loading} />}
            <div className="flex flex-col md:flex-row px-2 py-8 md:px-8 gap-4">
                <div className="md:w-3/4">
                    <CartHeader
                        cartItems={cart.cartItems}
                        selected={selected}
                        setSelected={setSelected}
                    />
                    <div className=" bg-white rounded py-2 px-4 border">
                        <h2 className="font-bold text-3xl my-2">
                            {t("cart.page.title")}
                        </h2>
                        <div className="w-full bg-slate-200 h-[1px]" />
                        {/*
                          * 🐛 key 가 배열 인덱스였다. 자식 `Product` 는 자기 선택 상태를
                          * 들고 있는데, 가운데 항목을 지우면 뒤 항목들의 인덱스가 하나씩
                          * 당겨지면서 React 가 **앞 항목의 상태를 뒤 항목에 붙인다.**
                          * "1번을 지웠는데 2번이 체크돼 있다"가 이것이다.
                          *
                          * `_uid` 는 상품·색상·사이즈 조합을 식별한다(Infos.jsx 가 만든다).
                          * 같은 상품이라도 색상이 다르면 다른 줄이므로 productId 로는 부족하다.
                          */}
                        {/* [JA] 🐛 key が配列インデックスだった。子の `Product` は自分の選択状態を持っているが、真ん中の項目を消すと
                            後ろの項目のインデックスが一つずつ詰まり、React が **前の項目の状態を後ろの項目に付ける。**
                            「1 番を消したのに 2 番がチェックされている」がこれである。
                            `_uid` は商品 · 色 · サイズの組み合わせを識別する(Infos.jsx が作る)。
                            同じ商品でも色が違えば別の行なので productId では足りない。 */}
                        {cart.cartItems.map((product, i) => (
                            <Product
                                product={product}
                                key={product._uid ?? i}
                                selected={selected}
                                setSelected={setSelected}
                                cart={cart}
                            />
                        ))}
                    </div>
                </div>
                <div className="md:w-1/4">
                    <Checkout
                        subtotal={subTotal}
                        shippingFee={shippingFee}
                        total={total}
                        selected={selected}
                        saveCartToDbHandler={saveCartToDbHandler}
                    />
                    {saveError && (
                        <p className="mt-2 text-red-600 text-sm" role="alert">{saveError}</p>
                    )}
                    <PaymentMethods />
                </div>
            </div>
        </>
    );
};

export default CartPage;



