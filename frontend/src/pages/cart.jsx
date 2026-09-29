import Header from "pages/header/Header";
import MenuSideBar from "./header/MenuSidebar";
import CartPage from "components/cart/CartPage";
import Empty from "components/cart/Empty";
import { useSelector, useDispatch } from "react-redux";
import { getRequest } from "util/api";
import { useFetcher, useLoaderData } from "react-router-dom";
import { syncedFromServer } from "../redux/CartSlice";
import { useEffect, useState } from "react";
import tokenUtil from "util/tokenUtil";
import { useFetchCart } from "hook/hooks";
import { useAuthContext } from "store/AuthContext";
import { useCallback } from "react";
import { jwtDecode } from "jwt-decode";
import { loadCart } from "util/api";
import DotLoaderSpinner from "components/loader/Loading";

const Cart = () => {
    
    const { user } = useAuthContext();
    const cart = useSelector((state) => { return state.cart; });
    const dispatch = useDispatch();   
                
    
    const { cartData, isSuccessCart, isPendingCart } = useFetchCart(user.email, cart.status, cart.cartId);

    
    /**
     * 로그인한 사용자의 장바구니는 **서버가 기준이다**(docs/40 0-b) — 들어올 때마다(그리고 창으로 돌아올 때마다) 서버 것으로 바꾼다.
     * 다른 기기에서 담거나 뺀 것이 여기서 보인다.
     *
     * <p>예전에는 "한 번도 손대지 않았을 때만" 채웠다. 서버 사본이 마지막 체크아웃의 스냅샷이라 방금 지운 물건이 되살아났기 때문인데,
     * 이제 담기·수량·삭제가 바로 서버에 반영되므로 서버 사본이 곧 지금 장바구니다.
     *
     * <p>이 브라우저의 장바구니가 아직 내 것으로 맞춰지지 않았으면(주인 없음 — 로그인 전에 담은 것, 또는 다른 사람의 것) 여기서
     * 덮지 않는다. CartSync 가 로그인 때 주인 없는 줄을 서버에 올린 뒤 채운다 — 여기서 먼저 덮으면 그 줄이 사라진다.
     */
    const uid = user?.userId ?? null;
    useEffect(() => {
        if (isSuccessCart && uid && cart.ownerId === uid) {
            dispatch(syncedFromServer({ items: cartData, ownerId: uid }));
        }
    }, [cartData, isSuccessCart, uid, cart.ownerId, dispatch])


    if(isPendingCart) return <DotLoaderSpinner loading={isPendingCart}/>;

    // 서버를 못 읽었으면(503) 가진 목록을 그대로 그린다 — 예전에는 빈 화면이었다. 처음부터 가진 것이 없으면 useFetchCart 가
    // 오류 화면으로 보낸다(throwWithoutData).
    
    

    return (
        <>
            <Header />
            <main className="w-full h-screen">
                {cart.cartItems.length > 0 ? (
                    <CartPage cart={cart} />
                ) : (
                    <Empty />
                )}
                
            </main>
            <MenuSideBar />
        </>
    );
};

export default Cart;

