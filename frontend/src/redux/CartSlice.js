import { createSlice } from "@reduxjs/toolkit";
import { cartStockKey } from "util/stock";

export const CartStatus = Object.freeze({
    
    EMPTY: 'empty',
    UPDATED : 'updated',
    COMMITED : 'commited',
    CANCELED: 'canceled',    
  });
  

const initialState = {
    cartItems: [],
    cartId : null,
    status : CartStatus.EMPTY,
    /**
     * 이 장바구니가 **누구의 서버 장바구니를 비춘 것인가**(사용자 id). null 이면 이 브라우저에만 있는 장바구니다 — 로그인 전에
     * 담은 것, 또는 서버 기준으로 바뀌기 전(docs/40 0-b)의 옛 상태. 로그인하면 CartSync 가 그 줄을 서버에 올리고 이 값을 채운다.
     *
     * <p>🐛 예전에는 주인이 없었다 — 장바구니가 localStorage 에 남아, 같은 브라우저에서 다른 사람이 로그인하면 앞 사람의
     * 장바구니가 보였고 체크아웃하면 그 사람 서버 장바구니로 들어갔다.
     */
    ownerId : null,
};

/**
 * 서버 목록으로 바꾸되, CartPage 가 받아 둔 **지금 재고** 표시(syncStock)는 같은 줄이면 이어 간다 — 재고 조회는 키가 같으면
 * 다시 돌지 않으므로, 버리면 "남은 수량 N개" 가 담기·수량 변경 한 번에 사라진다.
 */
const withStockOf = (previous, items) => {
    const byKey = new Map(previous.filter((i) => i.stockStatus !== undefined).map((i) => [cartStockKey(i), i]));
    return (items ?? []).map((item) => {
        const before = byKey.get(cartStockKey(item));
        return before ? { ...item, quantity: before.quantity, onHold: before.onHold, stockStatus: before.stockStatus } : item;
    });
};

export const cartSlice = createSlice({
    name: "cart",
    initialState,
    reducers: {
        /**
         * 사용자가 담았다. 이 순간부터 이 장바구니는 **사용자가 손댄 것**이다.
         */
        addToCart(state, action) {
            state.cartItems.push(action.payload);
            state.status = CartStatus.UPDATED;
        },

        /**
         * 사용자가 고쳤다 — 수량 변경·항목 삭제.
         *
         * <p>⚠️ `state.status = UPDATED` 가 주석 처리돼 있었다. 그래서 상태가 계속 EMPTY 로
         * 남았고, 장바구니 화면이 "비어 있으면 서버 사본으로 채운다"를 다시 실행했다 —
         * **지운 물건이 되살아났다.** 서버 사본은 마지막 체크아웃 시점의 스냅샷이라
         * 사용자가 방금 지운 것이 그대로 들어 있다.
         *
         * <p>사용자가 손댄 사실을 기록해야 "비어 있다"와 "비웠다"를 구분할 수 있다.
         */
        updateCart(state , action) {
            state.cartItems = action.payload;
            state.status = CartStatus.UPDATED;
        },

        /**
         * 서버 사본으로 채운다 — 사용자의 편집이 아니다.
         *
         * <p>updateCart 와 나눈 이유: 둘 다 목록을 통째로 바꾸지만 **의미가 다르다.**
         * 하나는 "사용자가 이렇게 바꿨다"이고 하나는 "서버에 있던 것을 가져왔다"이다.
         * 그런데 채운 뒤에도 상태는 EMPTY 가 아니어야 한다 — 그러지 않으면 사용자가
         * 전부 지웠을 때 다시 채우기가 돌아 같은 버그로 되돌아간다.
         */
        hydrateCart(state, action) {
            state.cartItems = withStockOf(state.cartItems, action.payload);
            state.status = CartStatus.UPDATED;
        },

        /**
         * 서버가 돌려준 **지금 장바구니**(loadcart·담기·수량·삭제의 응답)로 바꾸고 주인을 적는다(docs/40 0-b).
         * 로그인한 사용자의 장바구니는 이 액션으로만 바뀐다 — 서버가 기준이다.
         *
         * @param action.payload `{ items: [...] | null, ownerId }` — 서버는 장바구니가 없으면 null 을 준다
         */
        syncedFromServer(state, action) {
            const { items, ownerId } = action.payload ?? {};
            state.cartItems = withStockOf(state.cartItems, items);
            state.ownerId = ownerId ?? null;
            state.status = CartStatus.UPDATED;
        },

        /** 로그아웃 — 서버 장바구니의 사본은 지운다(서버에 남아 있다). 브라우저에만 있던 장바구니(주인 없음)는 그대로 둔다. */
        forgetOwner(state) {
            if (state.ownerId == null) return;
            state.cartItems = [];
            state.cartId = null;
            state.ownerId = null;
            state.status = CartStatus.EMPTY;
        },
        /**
         * 서버가 알려 준 **지금** 재고로 항목의 수량 정보를 바꾼다(docs/59 §5) — 담을 때 받은 값은 며칠 전 것일 수 있다.
         *
         * <p>사용자의 편집이 아니므로 status 를 건드리지 않는다. 담은 수량(qty)도 바꾸지 않는다 — 줄일지는 사용자가 정한다
         * (화면이 "남은 수량 N개 — 수량을 줄여 주세요" 라고 알린다).
         *
         * <p>물은 키가 응답에 없으면 그 옵션은 더 이상 없다(판매 종료·옵션 삭제) — `UNAVAILABLE`. 묻지 않은 항목(키를 만들 수
         * 없는 옛 항목)은 그대로 둔다.
         *
         * @param action.payload `{ stock: {키: {quantity, onHold, status}}, asked: [키...] }`
         */
        syncStock(state, action) {
            const { stock = {}, asked = [] } = action.payload ?? {};
            const askedSet = new Set(asked);
            state.cartItems.forEach((item) => {
                const key = cartStockKey(item);
                if (!key || !askedSet.has(key)) return;
                const now = stock[key];
                if (now) {
                    item.quantity = now.quantity;
                    item.onHold = now.onHold ?? 0;
                    item.stockStatus = now.status ?? null;
                } else {
                    item.stockStatus = "UNAVAILABLE";
                }
            });
        },
        emptyCart(state , action) {
            state.cartItems = [];
            state.cartId = null;
            state.status = CartStatus.EMPTY;
        },
        commitCart(state, action) {
            state.status = CartStatus.COMMITED;
            state.cartId = action.payload;
        },
        cancelCart(state , action) {         
            state.cartItems = [];
            state.status = CartStatus.CANCELED;
        }
    },
});

export const { addToCart, updateCart, hydrateCart, syncedFromServer, forgetOwner, syncStock, emptyCart, commitCart, cancelCart } = cartSlice.actions;

export default cartSlice.reducer;
