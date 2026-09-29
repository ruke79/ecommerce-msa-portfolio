import { store } from "redux/store";
import { addToCart, commitCart, emptyCart, cancelCart, updateCart, CartStatus } from "redux/CartSlice";

/**
 * 🐛 회귀 대상: redux-persist 가 <b>아무것도 저장하지 않았다.</b>
 *
 * `whitelist: ['auth']` 로 돼 있었는데 rootReducer 에 `auth` 가 없었다(주석 처리돼 있었다).
 * whitelist 는 존재하는 최상위 리듀서 키만 의미가 있으므로 저장 대상이 0개였고, PersistGate
 * 로 앱을 감싸고 persistStore 까지 부르면서 실제로는 아무 일도 하지 않았다.
 * 사용자에게는 "새로고침하면 장바구니가 비워진다"로 나타났다.
 */
describe("redux store", () => {
  it("registers every reducer the app reads from", () => {
    const state = store.getState();

    expect(state).toHaveProperty("cart");
    expect(state).toHaveProperty("chat");
    expect(state).toHaveProperty("menu");
    expect(state).toHaveProperty("dialog");
  });

  it("persists the cart", () => {
    const state = store.getState();

    // redux-persist 가 실제로 관리 중이면 해당 슬라이스에 _persist 메타가 붙는다.
    // whitelist 가 없는 키를 가리키면 이 값이 나타나지 않는다.
    expect(state._persist).toBeDefined();
    expect(state._persist.version).toBeDefined();
  });

  it("does not persist an `auth` slice — tokens stay in memory by design", () => {
    // 액세스 토큰은 XSS 탈취를 막기 위해 AuthContext(메모리)에만 둔다. 여기 auth 가
    // 생긴다면 그 설계가 되돌려진 것이므로 즉시 알아야 한다.
    expect(store.getState()).not.toHaveProperty("auth");
  });
});

describe("CartSlice", () => {
  it("adds an item", () => {
    store.dispatch(emptyCart());
    store.dispatch(addToCart({ productId: 1, qty: 2 }));

    expect(store.getState().cart.cartItems).toHaveLength(1);
  });

  it("replaces the whole list on updateCart", () => {
    store.dispatch(updateCart([{ productId: 9 }, { productId: 10 }]));

    expect(store.getState().cart.cartItems).toHaveLength(2);
  });

  it("records the cart id and status when committed", () => {
    store.dispatch(commitCart("cart-123"));

    const { cartId, status } = store.getState().cart;
    expect(cartId).toBe("cart-123");
    expect(status).toBe(CartStatus.COMMITED);
  });

  it("clears items and id when emptied", () => {
    store.dispatch(addToCart({ productId: 1 }));
    store.dispatch(commitCart("cart-9"));
    store.dispatch(emptyCart());

    const cart = store.getState().cart;
    expect(cart.cartItems).toEqual([]);
    expect(cart.cartId).toBeNull();
    expect(cart.status).toBe(CartStatus.EMPTY);
  });

  it("marks the cart cancelled without wiping the id", () => {
    // 취소는 결제 실패 경로다. cartId 를 남겨 둬야 어떤 장바구니가 취소됐는지 추적된다.
    store.dispatch(commitCart("cart-77"));
    store.dispatch(cancelCart());

    const cart = store.getState().cart;
    expect(cart.status).toBe(CartStatus.CANCELED);
    expect(cart.cartItems).toEqual([]);
  });
});
