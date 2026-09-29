import { act, render, waitFor } from "@testing-library/react";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";

import CartSync from "components/cart/CartSync";
import cartReducer, { addToCart, syncedFromServer } from "redux/CartSlice";
import { loadCart, putCartItem } from "util/api";

let mockAuth;
jest.mock("store/AuthContext", () => ({
    STATUS: { IDLE: "idle", PENDING: "pending", SUCCEEDED: "succeeded", FAILED: "failed" },
    useAuthContext: () => mockAuth,
}));
jest.mock("util/api", () => ({ __esModule: true, default: {}, loadCart: jest.fn(), putCartItem: jest.fn() }));

const item = (id, over = {}) => ({ _uid: `u${id}`, id, color: { id: "5" }, size: "M", qty: 1, ...over });
const signedIn = (userId) => ({ status: "succeeded", user: { userId }, token: "t" });

const storeWith = (...actions) => {
    const store = configureStore({ reducer: { cart: cartReducer } });
    actions.forEach((a) => store.dispatch(a));
    return store;
};

const mount = (store) => {
    const view = render(<Provider store={store}><CartSync /></Provider>);
    return { rerender: () => view.rerender(<Provider store={store}><CartSync /></Provider>) };
};

beforeEach(() => {
    jest.clearAllMocks();
    putCartItem.mockResolvedValue([]);
});

/**
 * 로그인·로그아웃 경계에서 장바구니를 서버와 맞춘다(docs/40 0-b).
 */
describe("CartSync — 로그인 때 한 번 서버와 맞추기", () => {
    it("🔴 로그인 전에 담은 줄(주인 없음)은 서버에 올리고, 서버 장바구니를 받아 내 것으로 적는다", async () => {
        const store = storeWith(addToCart(item("1", { stockStatus: "ACTIVE", onHold: 1 })));
        loadCart.mockResolvedValue([item("1"), item("9")]);   // 9 는 다른 기기에서 담은 것
        mockAuth = signedIn("42");

        mount(store);

        await waitFor(() => expect(store.getState().cart.ownerId).toBe("42"));
        // 화면 표시(재고)는 서버에 보내지 않는다
        expect(putCartItem).toHaveBeenCalledWith(item("1"));
        expect(store.getState().cart.cartItems.map((i) => i.id)).toEqual(["1", "9"]);
    });

    it("🔴 다른 사람의 장바구니(같은 브라우저의 앞 사람)는 올리지 않고 서버의 내 장바구니로 바꾼다", async () => {
        const store = storeWith(syncedFromServer({ items: [item("7")], ownerId: "41" }));
        loadCart.mockResolvedValue(null);
        mockAuth = signedIn("42");

        mount(store);

        await waitFor(() => expect(store.getState().cart.ownerId).toBe("42"));
        expect(putCartItem).not.toHaveBeenCalled();
        expect(store.getState().cart.cartItems).toEqual([]);
    });

    it("이미 내 장바구니면 올리지 않고 서버 것만 받는다", async () => {
        const store = storeWith(syncedFromServer({ items: [item("1")], ownerId: "42" }));
        loadCart.mockResolvedValue([item("2")]);
        mockAuth = signedIn("42");

        mount(store);

        await waitFor(() => expect(store.getState().cart.cartItems.map((i) => i.id)).toEqual(["2"]));
        expect(putCartItem).not.toHaveBeenCalled();
    });

    it("한 줄이 안 올라가도(판매 종료) 나머지를 올리고 맞춘다", async () => {
        const store = storeWith(addToCart(item("1")), addToCart(item("2")));
        putCartItem.mockRejectedValueOnce({ response: { status: 400 } });
        loadCart.mockResolvedValue([item("2")]);
        mockAuth = signedIn("42");

        mount(store);

        await waitFor(() => expect(store.getState().cart.ownerId).toBe("42"));
        expect(putCartItem).toHaveBeenCalledTimes(2);
    });

    it("새로고침 직후(인증 확인 중)에는 아무것도 하지 않는다 — 토큰이 아직 없다", () => {
        const store = storeWith(syncedFromServer({ items: [item("1")], ownerId: "42" }));
        mockAuth = { status: "pending", user: {}, token: null };

        mount(store);

        expect(loadCart).not.toHaveBeenCalled();
        expect(store.getState().cart.cartItems).toHaveLength(1);
    });

    it("🔴 로그아웃하면 서버 장바구니의 사본을 지운다", () => {
        const store = storeWith(syncedFromServer({ items: [item("1")], ownerId: "42" }));
        mockAuth = { status: "idle", user: {}, token: null };

        mount(store);

        expect(store.getState().cart.cartItems).toEqual([]);
        expect(store.getState().cart.ownerId).toBeNull();
    });

    it("맞추는 사이에 로그아웃했으면 받은 장바구니를 버린다 — 다음 사람에게 남지 않는다", async () => {
        const store = storeWith();
        let answer;
        loadCart.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
        mockAuth = signedIn("42");
        const view = mount(store);
        await waitFor(() => expect(loadCart).toHaveBeenCalled());

        mockAuth = { status: "idle", user: {}, token: null };
        view.rerender();
        await act(async () => answer([item("1")]));

        expect(store.getState().cart.cartItems).toEqual([]);
        expect(store.getState().cart.ownerId).toBeNull();
    });

    it("서버를 못 읽으면 로그인 전에 담은 줄은 그대로 둔다(주인 없음 — 다음에 다시 맞춘다)", async () => {
        const store = storeWith(addToCart(item("1")));
        loadCart.mockRejectedValue({ response: { status: 503 } });
        mockAuth = signedIn("42");

        mount(store);

        await waitFor(() => expect(loadCart).toHaveBeenCalled());
        await waitFor(() => expect(store.getState().cart.cartItems.map((i) => i.id)).toEqual(["1"]));
        expect(store.getState().cart.ownerId).toBeNull();
    });
});
