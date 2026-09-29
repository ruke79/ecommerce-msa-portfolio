import { useDispatch } from "react-redux";
import { useTranslation } from "i18n/I18nProvider";
import { useMoney } from "hook/useMoney";
import {
    HeartIcon,
    MinusIcon,
    PlusIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";
import { Link, useLoaderData } from "react-router-dom";
import { useEffect, useState } from "react";
import { syncedFromServer, updateCart } from "../../redux/CartSlice";
import { cartLineStockOf } from "util/stock";
import { removeCartItem, setCartItemQty } from "util/api";
import { useAuthContext } from "store/AuthContext";


const Product = ({ product, selected, setSelected,  cart }) => {
    const { t } = useTranslation();
    const money = useMoney();
    const dispatch = useDispatch();
    const { cartItems } = cart;
    const [active, setActive] = useState();
    const { token, user } = useAuthContext();
    const [busy, setBusy] = useState(false);
    const [lineError, setLineError] = useState("");

    /**
     * 로그인한 사용자의 장바구니(서버 장바구니를 비춘 것)면 수량·삭제를 서버에 바로 반영하고 응답으로 화면을 바꾼다(docs/40 0-b).
     * 예전에는 브라우저에만 반영돼 다른 기기에서는 옛 장바구니가 보였고, 체크아웃에서 고르지 않은 줄은 서버에서 사라졌다.
     * 실패하면 화면은 그대로 — 서버와 어긋난 화면을 만들지 않는다.
     */
    const onServer = Boolean(token && user?.userId && cart.ownerId === user.userId);
    const line = { productId: product.id, colorId: product.color?.id, size: product.size };

    const applyOnServer = async (call) => {
        setBusy(true);
        setLineError("");
        try {
            const items = await call();
            dispatch(syncedFromServer({ items, ownerId: user.userId }));
            return true;
        } catch (err) {
            setLineError(err?.response?.data?.message ?? t("cart.page.update_failed"));
            return false;
        } finally {
            setBusy(false);
        }
    };
 
        


    useEffect(() => {
        const check = selected.find((p) => p._uid === product._uid);
        setActive(check);
    }, [selected]);

    const updateQty = async (type) => {
        const qty = type === "plus" ? product.qty + 1 : product.qty - 1;
        if (onServer) {
            if (!(await applyOnServer(() => setCartItemQty({ ...line, qty })))) return;
        } else {
            dispatch(updateCart(cartItems.map((p) => (p._uid === product._uid ? { ...p, qty } : p))));
        }
        // 고른 목록은 고를 때의 복사본이다 — 수량을 맞춰 둔다(합계가 그것으로 계산된다).
        if (selected.find((p) => p._uid === product._uid)) {
            setSelected(selected.map((p) => (p._uid === product._uid ? { ...p, qty } : p)));
        }
    };

    const removeItemCart = async (product) => {
        if (onServer) {
            if (!(await applyOnServer(() => removeCartItem(line)))) return;
        } else {
            dispatch(updateCart(cartItems.filter((p) => p._uid !== product._uid)));
        }
        setSelected(selected.filter((p) => p._uid !== product._uid));
    };

    const handleSelect = () => {
        const check = selected.find((p) => p._uid === product._uid);
        if (check) {
            setSelected(selected.filter((p) => p._uid !== product._uid));
        } else {
            setSelected([...selected, product]);
        }
    };

    // 지금 재고 기준의 문제 — 장바구니 화면이 들어올 때마다 서버에서 새로 받는다(CartPage, docs/59 §5).
    const stock = cartLineStockOf(product);
    const atMax = typeof product.quantity === "number" && product.qty >= product.quantity;

    return (
        <div className="mt-2 grid grid-cols-3  max-md:grid-rows-1 md:grid-cols-6  border-b p-2 pb-4 last:border-none ">
            <div className="flex flex-col-reverse md:flex-row md:items-center">
                <input
                    type="checkbox"
                    name="product"
                    className="w-5 h-5 cursor-pointer hidden md:block"
                    onChange={() => handleSelect()}
                    defaultChecked={false}
                    checked={active}
                />
                <img
                    src={product.images[0]}
                    width={100}
                    height={100}
                    className="md:ml-4 object-contained rounded-md outline outline-1 outline-offset-2 outline-slate-300"
                    alt={product.name}
                />
            </div>
            <div className="col-span-2  md:col-span-4 ml-4 md:ml-2">
                <Link to="" target="_blank" className="text-sm font-semibold">
                    {product.name}
                </Link>
                <div className="my-2 w-fit  flex items-center space-x-3 px-3 py-2 bg-slate-100 rounded-full">
                    <div className="relative w-10 h-10">
                        <img
                            src={product.color.colorImage}                            
                            className="object-contained rounded-full outline outline-1 outline-offset-2 outline-slate-400"
                            alt={product.name}
                        />
                    </div>
                    <span>{product.size}</span>
                    <span>{money(product.price)}</span>
                </div>
                <div className="flex flex-col md:flex-row md:items-center">
                    <span className="font-bold md:text-xl">
                        {money(Number(product.price) * product.qty)}
                    </span>
                    <span className="ml-2 text-sm line-through text-slate-400">
                        {money(Number(product.priceBefore) * product.qty)}
                    </span>
                </div>
                <span className="text-blue-500 text-sm">
                    {product.shipping === 0
                        ? t("shipping.free")
                        : t("shipping.fee", { amount: money(product.shipping) })}
                </span>
            </div>
            <div className="max-md:col-span-3 max-md:items-center max-md:mt-2 max-md:justify-between flex md:mt-4  md:flex-col md:items-end md:justify-center">
                <div className="flex items-center space-x-4">
                    <input
                        type="checkbox"
                        name="product"
                        className="w-5 h-5 cursor-pointer md:hidden"
                        onChange={() => handleSelect()}
                        checked={active}
                    />
                    <HeartIcon className="w-6 h-6 cursor-pointer" />
                    <TrashIcon
                        className="w-6 h-6 cursor-pointer"
                        aria-label={t("cart.page.remove")}
                        data-testid="cart-line-remove"
                        onClick={() => !busy && removeItemCart(product)}
                    />
                </div>
                <div className="md:mt-4  flex items-center space-x-2">
                    <button
                        disabled={busy || product.qty < 2}
                        className="bg-slate-200 p-1.5 rounded-full hover:bg-slate-300"
                        onClick={() => updateQty("minus")}
                    >
                        <MinusIcon className="w-4 h-4  text-slate-800" />
                    </button>
                    <span className="text-m text-slate-900">{product.qty}</span>
                    <button
                        disabled={busy || atMax}
                        className="bg-slate-200 p-1.5 rounded-full hover:bg-slate-300"
                        onClick={() => updateQty("plus")}
                    >
                        <PlusIcon className="w-4 h-4  text-slate-900" />
                    </button>
                </div>
            </div>
            {lineError && (
                <p className="col-span-3 md:col-span-6 mt-2 text-sm text-red-600" role="alert" data-testid="cart-line-error">
                    {lineError}
                </p>
            )}
            {stock && (
                <p
                    className={`col-span-3 md:col-span-6 mt-2 text-sm ${stock.kind === "on_hold" ? "text-amber-700" : "text-red-600"}`}
                    role="status"
                    data-testid="cart-line-stock"
                    data-kind={stock.kind}
                >
                    {t(`cart.stock.${stock.kind}`, { count: stock.count })}
                </p>
            )}
        </div>
    );
};

export default Product;
