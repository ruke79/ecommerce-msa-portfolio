import { Rating } from "@mui/material";
import { useTranslation } from "i18n/I18nProvider";
import { useEffect, useState } from "react";
import {Link, useNavigate} from "react-router-dom";

import {
    HeartIcon,
    MinusIcon,
    PlusIcon,
    ShoppingBagIcon,
} from "@heroicons/react/24/outline";
import AccoridanProduct from "./AccordianProduct";
import RestockButton from "./RestockButton";
import { useMoney } from "hook/useMoney";
import { rangeText } from "util/priceText";
import api, { getRequest, putRequest, putCartItem } from "util/api";
import { useDispatch, useSelector } from "react-redux";
import { addToCart, emptyCart, syncedFromServer, updateCart } from "../../redux/CartSlice";
import { showDialog } from "../../redux/DialogSlice";
import { ArrowPathIcon } from "@heroicons/react/24/solid";
import { useAuthContext } from "../../store/AuthContext";
import { useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";


const getCartData = async({productId, style, size}) => {
    const { data }  = await getRequest(`/catalog-service/api/cart/${productId}?style=${style}&size=${size}`);    
    return data;
}



const Infos = ({ product, num_reviews, setActiveImg }) => {
    const { token, user } = useAuthContext();
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    
    const dispatch = useDispatch();
    const { t } = useTranslation();
    const money = useMoney();
    const { cartItems: cart } = useSelector((state) => state.cart);

    
        
    // 사이즈 없는 상품은 고를 것이 없다 — 첫 행(0)을 고른 것으로 본다. loader 도 ?size 가 없으면 0 번을 쓴다.
    const sizeParam = product.sizeless ? (searchParams.get('size') ?? "0") : searchParams.get('size');
    const styleParam = searchParams.get('style');    

    const [loading, setLoading] = useState(false);
    const [size, setSize] = useState(sizeParam);
    const [qty, setQty] = useState(1);
    const [error, setError] = useState("");
    /** 고른 사이즈가 지금은 0 개지만 다른 손님의 결제 대기 때문일 때(docs/59 §5). */
    const heldOut = sizeParam !== null && product.quantity < 1 && (product.onHold ?? 0) > 0;
    /** 관리자가 품절로 내린 상품 — 수량이 남아 있어도 팔지 않는다(목록 카드와 같은 판단 — util/stock). */
    const discontinued = product.status === "SOLD_OUT";

    const { mutate : saveCartOp } = useMutation({    
        mutationFn : getCartData, 
        // 담기 실패는 이 화면의 문구로 — 전역 기본값(throwOnError)이면 화면 전체가 오류 화면으로 바뀐다.
        throwOnError: false,
        onError: (error) => {
            setError(error?.response?.data?.message ?? t("product.error.out_of_stock"));
            setLoading(false);
        },
        onSuccess: (data) => {
            // 🐛 서버가 빈 본문(품절로 내린 상품)을 주면 수량이 undefined 라 아래 두 비교가 모두 거짓 — 그대로 담았다.
            if (!data || typeof data.quantity !== "number") {
                setError(t("product.error.out_of_stock"));
                setLoading(false);
                return;
            }
    
            // 0 개를 먼저 본다 — 예전 순서로는 0 개도 "재고보다 많은 수량" 으로 나왔다. 남은 게 없는데 누가 결제 중이면
            // 품절이 아니다(결제가 안 되면 돌아온다 — docs/59 §5).
            if (data.quantity < 1) {
                setError(t((data.onHold ?? 0) > 0 ? "product.on_hold" : "product.error.out_of_stock"));
                setLoading(false);
                return;
            } else if (qty > data.quantity) {
                setError(
                    t("product.error.too_many")
                );
                setLoading(false);
            } else {
                // 줄 식별자 — 상품 id 로. 예전에는 상품명이었는데, 이름이 같은 다른 상품(시드에 "Digital Camera" 가 여럿)이 같은 값을
                // 가져 장바구니에서 하나를 고르면 전부 선택됐다. 로그인했으면 서버가 줄 id 로 다시 준다.
                let _uid = `${product.id}_${product.style}_${sizeParam}`;

                // 로그인했으면 서버에 담는다 — 서버가 장바구니의 기준이다(docs/40 0-b). 같은 줄이면 서버가 수량을 이 값으로
                // 바꾸고, 응답(지금 장바구니 전체)으로 화면을 바꾼다. 다른 기기에서도 같은 장바구니가 보인다.
                if (token && user?.userId) {
                    putCartItem({ ...data, qty, size: data.size, _uid })
                        .then((items) => {
                            dispatch(syncedFromServer({ items, ownerId: user.userId }));
                            setError("");
                        })
                        .catch((err) => setError(err?.response?.data?.message ?? t("cart.page.update_failed")))
                        .finally(() => setLoading(false));
                    return;
                }

                // 로그인 전 — 이 브라우저에만 담는다. 로그인하면 CartSync 가 서버에 올린다.
                let exist = cart.find((p) => p._uid === _uid);
                if (exist) {
                    
                    let newCart = cart.map((p) => {
                        if (p._uid === exist._uid) {
                            return { ...p, qty: qty };
                        }
                        return p;
                    });
                    
                    dispatch(updateCart(newCart));
                    setError("");
                    setLoading(false);
                } else {       
                    
                    dispatch(addToCart({ ...data, qty, size: data.size, _uid }));
    
                    setError("");
                    setLoading(false);
                }
                
            }
        },
        
    });

    
    useEffect(() => {
        setSize("");
        setQty(1);
    }, [styleParam]);

    useEffect(() => {
        if (qty > product.quantity) {
            setQty(product.quantity);
        }
    }, [sizeParam]);

    const addToCartHandler = async () => {
        setLoading(true);
        if (discontinued) {
            setError(t("product.error.out_of_stock"));
            setLoading(false);
            return;
        }
        if (!sizeParam) {
            setError(t("product.error.select_size"));
            setLoading(false);
            return;
        }
     
        saveCartOp({productId : product.id, style : product.style, size : sizeParam});
    };

    const handleWishlist = async () => {
        try {
            if (!token) {
                return navigate('/signin');
            }
            
            const { data } = await putRequest("/user-service/api/user/wishlist", {
                id: product.id,
                style: product.style,
            });
            dispatch(
                showDialog({
                    header: t("product.wishlist.added"),
                    msgs: [
                        {
                            msg: data.message,
                            type: "success",
                        },
                    ],
                })
            );
        } catch (error) {
            dispatch(
                showDialog({
                    header: t("product.wishlist.failed"),
                    msgs: [
                        {
                            msg: error.response?.data.message,
                            type: "error",
                        },
                    ],
                })
            );
        }
    };

    return (
        <div className="flex flex-col row-span-3 md:col-span-3 max-md:px-2 mb-4">
            <h1 className="text-2xl font-bold ">{product.name}</h1>
            {/* 고른 사이즈의 SKU — 문의 · 반품 때 "어느 것" 인지 말할 수 있게(docs/65). */}
            {product.sku && (
                <div data-testid="product-sku" className="text-xs text-slate-500 font-mono">
                    {t("product.sku", { sku: product.sku })}
                </div>
            )}
            <div className="flex items-center ">
                <span className="cursor-pointer uppercase hover:underline text-sm mr-3 text-slate-600">
                    {product.brand}
                </span>
                <Rating
                    name="half-rating-read"
                    defaultValue={product.rating}
                    precision={0.5}
                    readOnly
                    style={{ color: "#FACF19" }}
                />
                <span className="text-slate-500">
                    {/* ({product.num_reviews}{" "}
                    {product.num_reviews >= 1 ? "reviews" : "review"}) */}
                    {/* 🌏 영어의 단수/복수 분기를 없앤다. 한국어·일본어에는 복수형이
                        없어서 "리뷰 1개"·"レビュー1件" 이 그대로 맞다(docs/45 §3). */}
                    {t("product.reviews", { count: num_reviews })}
                </span>
            </div>
            <div className="flex w-full bg-slate-200 h-[1px]" />

            <div className="mt-2 flex items-center">
                <div className="text-4xl font-semibold text-red-500">
                    {!size && !product.sizeless ? rangeText(product.priceRange, money) : money(product.price)}
                </div>

                {product.discount > 0 ? (
                    <div className="ml-2  text-slate-400">
                        {(size || product.sizeless) && (
                            <span className=" text-xl line-through">
                                {money(product.priceBefore)}
                            </span>
                        )}
                        <span className="ml-1 text-blue-500">
                            (-{product.discount}%)
                        </span>
                    </div>
                ) : (
                    ""
                )}
            </div>

            <div className="mt-1  text-sm text-slate-500">
                {/* 🌏 예전에는 "pieces Available" 이 번역되지 않은 채 남아 있었다. */}
                {t("product.stock_left", {
                    count: discontinued ? 0
                        : size || product.sizeless ? product.quantity : product.sizes.reduce((start, next) => start + next.quantity, 0),
                })}
            </div>

            <p className=" mt-3  text-sm">{product.description}</p>

            {!product.sizeless && (
            <div className="mt-3 " data-testid="size-picker">
                <h4 className="font-semibold text-slate-700">{t("product.select_size")}</h4>
                <div className="mt-2 flex gap-3 ">
                    {product.sizes.map((size, i) => (
                        <Link
                            key={i}
                            to={`/product/${product.slug}?style=${Number(styleParam)}&size=${i}`}
                        >
                            <div
                                onClick={() => {setSize(size.size);} }
                                className={`flex items-center justify-center w-11 h-11 rounded-full bg-slate-200 text-slate-700 hover:outline hover:outline-1 hover:outline-slate-400 hover:outline-offset-[3px] transition-all transition  ${
                                    i === Number(sizeParam) &&
                                    "font-semibold outline outline-1 outline-slate-400 outline-offset-[3px] bg-gradient-to-r from-amazon-orange to-slate-100"
                                }`}
                            >
                                {size.size}
                            </div>
                        </Link>
                    ))}
                </div>
            </div>
            )}

            <div className="mt-2 ">
                <h4 className="font-semibold text-slate-700">
                    {t("product.select_color")}
                </h4>
                <div className="mt-2 flex gap-3">
                    {product.colors &&
                        product.colors.map((color, i) => (
                            <span
                                key={i}
                                className={`rounded-full w-11 h-11 hover:outline outline-1 hover:outline-offset-[3px] hover:outline-slate-400  transition-all transition ${
                                    i === Number(styleParam)
                                        ? "outline outline-1 outline-offset-[3px] outline-slate-400"
                                        : ""
                                }`}
                                onMouseOver={() =>
                                    setActiveImg(
                                        product.skus[i].images[0]
                                    )
                                }
                                onMouseLeave={() => setActiveImg("")}
                            >
                                <Link
                                    to={`/product/${product.slug}/?style=${i}`}
                                >
                                    <img
                                        className="rounded-full object-cover h-11"
                                        width="44"
                                        height="44"
                                        src={color.colorImage}
                                        alt={color.color}
                                    />
                                </Link>
                            </span>
                        ))}
                </div>
            </div>

            <div className="mt-6  flex items-center space-x-2">
                <button
                    className="bg-slate-200 p-1.5 rounded-full hover:bg-slate-300"
                    onClick={() => qty > 1 && setQty((prev) => prev - 1)}
                >
                    <MinusIcon className="w-4 h-4  text-slate-800" />
                </button>
                <span className="text-m text-slate-900">{qty}</span>
                <button
                    className="bg-slate-200 p-1.5 rounded-full hover:bg-slate-300"
                    onClick={() =>
                        qty < product.quantity && setQty((prev) => prev + 1)
                    }
                >
                    <PlusIcon className="w-4 h-4  text-slate-900" />
                </button>
            </div>

            <div className="mt-2 flex flex-col md:flex-row md:space-x-3">
                <button
                    className={`flex flex-grow items-center justify-center bg-gradient-to-r from-amazon-orange to-yellow-300 text-amazon-blue_dark  p-2 rounded-full space-x-2 hover:text-slate-100 hover:from-amazon-blue_light hover:to-slate-500 hover:shadow-md transition duration-300 ${
                        product.quantity < 1 || discontinued ? "cursor-not-allowed" : ""
                    }`}
                    disabled={product.quantity < 1 || discontinued}
                    onClick={() => addToCartHandler()}
                >
                    {loading ? (
                        <>
                            <ArrowPathIcon className="w-8 h-8" />
                            <span className="font-semibold text-xl">
                                {t("product.loading")}
                            </span>
                        </>
                    ) : (
                        <>
                            <ShoppingBagIcon className="w-8 h-8" />
                            <span className="font-semibold text-xl">
                                {t("product.add_to_cart")}
                            </span>
                        </>
                    )}
                </button>

                <button
                    onClick={() => handleWishlist()}
                    className="flex items-center bg-slate-200 text-amazon-blue_light p-2 rounded space-x-2 hover:bg-amazon-blue_light hover:text-slate-100 transition duration-500 ease-in-out max-md:mt-3"
                >
                    <HeartIcon className="w-8 h-8" />
                    <span>{t("product.wishlist")}</span>
                </button>
            </div>
            {/* 남은 게 없지만 다른 손님이 결제 중인 재고라면 품절이 아니다 — 결제되지 않으면 곧 돌아온다(docs/59 §5).
                재입고 알림은 실물이 0 을 건널 때만 가므로 여기서 걸게 하면 알림이 오지 않는다. */}
            {discontinued && (
                <p className="mt-2 rounded border border-slate-300 bg-slate-100 p-2 text-sm text-slate-700" data-testid="discontinued">
                    {t("product.error.out_of_stock")}
                </p>
            )}
            {heldOut && (
                <p className="mt-2 rounded border border-amber-300 bg-amber-50 p-2 text-sm text-amber-900" data-testid="stock-on-hold">
                    {t("product.on_hold")}
                </p>
            )}
            {/* 선택한 사이즈가 품절이면 재입고 알림을 걸 수 있다(docs/53). */}
            <RestockButton
                product={product}
                colorId={product.colors?.[Number(styleParam) || 0]?.id ?? null}
                size={sizeParam !== null ? product.sizes?.[Number(sizeParam)]?.size : null}
                soldOut={sizeParam !== null && product.quantity < 1 && !heldOut}
            />

            <div className="m-2">
                {error && (
                    <span className="mt-2 text-red-500 font-semibold">
                        {error}
                    </span>
                )}
            </div>

            <div className="mt-4">
                <AccoridanProduct
                    details={product.details}
                    questions={product.questions}
                />
            </div>
        </div>
    );
};

export default Infos;
