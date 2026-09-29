import reducer, {
  CartStatus,
  addToCart,
  updateCart,
  hydrateCart,
  syncStock,
  emptyCart,
  syncedFromServer,
  forgetOwner,
} from "redux/CartSlice";

/**
 * 🐛 회귀 대상: **장바구니에서 지운 물건이 되살아났다.**
 *
 * 원인은 `updateCart` 안의 `state.status = UPDATED` 가 주석 처리돼 있던 것이다. 그래서
 * 사용자가 항목을 지워도 상태는 계속 `EMPTY` 로 남았고, 장바구니 화면의 "비어 있으면
 * 서버 사본으로 채운다"가 다시 실행됐다(pages/cart.jsx).
 *
 * 되살아나는 사본이 무엇인지가 특히 나쁘다 — 서버 장바구니는 `savecart`(체크아웃을 누를
 * 때만 호출된다)로 저장된 **마지막 체크아웃 시점의 스냅샷**이다. 방금 지운 물건이 거기
 * 그대로 들어 있다.
 *
 * 핵심은 "비어 있다"와 "사용자가 비웠다"를 구분하는 것이다. 그 구분이 status 다.
 */
describe("CartSlice — 비어 있다 vs 비웠다", () => {
  const item = (uid) => ({ _uid: uid, qty: 1, price: 100, shipping: 0 });

  it("사용자가 담으면 UPDATED 가 된다", () => {
    const next = reducer(undefined, addToCart(item("a")));

    expect(next.cartItems).toHaveLength(1);
    expect(next.status).toBe(CartStatus.UPDATED);
  });

  it("사용자가 마지막 항목을 지우면 비어 있어도 UPDATED 다 — 이게 버그의 핵심이다", () => {
    const withItem = reducer(undefined, addToCart(item("a")));

    // 삭제는 "남은 목록으로 통째로 교체"다(components/cart/Product.jsx).
    const emptied = reducer(withItem, updateCart([]));

    expect(emptied.cartItems).toHaveLength(0);
    expect(emptied.status)
      .not.toBe(CartStatus.EMPTY);   // EMPTY 면 화면이 서버 사본으로 다시 채운다
    expect(emptied.status).toBe(CartStatus.UPDATED);
  });

  it("서버 사본으로 채워도 EMPTY 로 되돌아가지 않는다 — 그러면 지운 뒤 다시 채워진다", () => {
    const hydrated = reducer(undefined, hydrateCart([item("a"), item("b")]));

    expect(hydrated.cartItems).toHaveLength(2);
    expect(hydrated.status).not.toBe(CartStatus.EMPTY);

    // 채운 뒤 사용자가 전부 지워도 EMPTY 로 돌아가면 안 된다.
    const emptied = reducer(hydrated, updateCart([]));
    expect(emptied.status).not.toBe(CartStatus.EMPTY);
  });

  it("손대지 않은 새 장바구니만 EMPTY 다 — 첫 방문의 서버 복원은 막지 않는다", () => {
    const fresh = reducer(undefined, { type: "@@INIT" });

    expect(fresh.cartItems).toHaveLength(0);
    expect(fresh.status).toBe(CartStatus.EMPTY);
  });

  it("주문이 끝나면 EMPTY 로 돌아간다 — 그때는 서버도 비어 있으므로 복원해도 안전하다", () => {
    const withItem = reducer(undefined, addToCart(item("a")));

    const done = reducer(withItem, emptyCart());

    expect(done.cartItems).toHaveLength(0);
    expect(done.cartId).toBeNull();
    expect(done.status).toBe(CartStatus.EMPTY);
  });
});


/**
 * 장바구니의 수량을 지금 값으로 (docs/59 §5) — 담을 때 받은 수량은 며칠 전 것일 수 있다.
 */
describe("syncStock — 지금 재고로 갱신", () => {
  const item = (over) => ({ _uid: over.id, id: over.id, color: { id: "5" }, size: "M", qty: 2, quantity: 9, ...over });
  const withItems = (items) => reducer(undefined, updateCart(items));

  it("수량·잡힌 수·상품 상태를 바꾸고, 담은 수량(qty)과 장바구니 상태는 그대로다", () => {
    const before = withItems([item({ id: "1" })]);
    const after = reducer(before, syncStock({
      stock: { "1:5:M": { quantity: 1, onHold: 2, status: "ACTIVE" } }, asked: ["1:5:M"] }));

    expect(after.cartItems[0]).toMatchObject({ quantity: 1, onHold: 2, stockStatus: "ACTIVE", qty: 2 });
    expect(after.status).toBe(before.status);
  });

  it("물었는데 응답에 없으면 판매 종료(UNAVAILABLE), 묻지 않은 항목은 건드리지 않는다", () => {
    const before = withItems([item({ id: "1" }), item({ id: "2" })]);
    const after = reducer(before, syncStock({ stock: {}, asked: ["1:5:M"] }));

    expect(after.cartItems[0].stockStatus).toBe("UNAVAILABLE");
    expect(after.cartItems[1]).toEqual(before.cartItems[1]);
  });

  it("다시 생기면(재입고) 판매 종료 표시가 풀린다", () => {
    let state = withItems([item({ id: "1" })]);
    state = reducer(state, syncStock({ stock: {}, asked: ["1:5:M"] }));
    state = reducer(state, syncStock({ stock: { "1:5:M": { quantity: 4, status: "ACTIVE" } }, asked: ["1:5:M"] }));

    expect(state.cartItems[0]).toMatchObject({ quantity: 4, onHold: 0, stockStatus: "ACTIVE" });
  });
});

/**
 * 서버가 장바구니의 기준이다(docs/40 0-b). 로그인한 사용자의 장바구니는 서버 응답으로만 바뀌고, 누구의 것인지(ownerId)를 적는다.
 * 🐛 예전에는 주인이 없어 같은 브라우저의 다음 사람에게 앞 사람의 장바구니가 보였다.
 */
describe("syncedFromServer · forgetOwner — 서버 장바구니의 사본", () => {
  const item = (over) => ({ _uid: over.id, id: over.id, color: { id: "5" }, size: "M", qty: 1, quantity: 9, ...over });

  it("서버 목록으로 바꾸고 주인을 적는다. 서버가 null(장바구니 없음)을 주면 빈 목록", () => {
    let state = reducer(undefined, addToCart(item({ id: "local" })));
    state = reducer(state, syncedFromServer({ items: [item({ id: "1" })], ownerId: "42" }));

    expect(state.cartItems.map((i) => i.id)).toEqual(["1"]);
    expect(state.ownerId).toBe("42");
    expect(state.status).toBe(CartStatus.UPDATED);

    state = reducer(state, syncedFromServer({ items: null, ownerId: "42" }));
    expect(state.cartItems).toEqual([]);
  });

  it("같은 줄이면 받아 둔 지금 재고 표시를 이어 간다 — 재고 조회는 키가 같으면 다시 돌지 않는다", () => {
    let state = reducer(undefined, syncedFromServer({ items: [item({ id: "1" })], ownerId: "42" }));
    state = reducer(state, syncStock({ stock: { "1:5:M": { quantity: 1, onHold: 2, status: "ACTIVE" } }, asked: ["1:5:M"] }));

    state = reducer(state, syncedFromServer({ items: [item({ id: "1", qty: 3, quantity: 9 }), item({ id: "2" })], ownerId: "42" }));

    expect(state.cartItems[0]).toMatchObject({ qty: 3, quantity: 1, onHold: 2, stockStatus: "ACTIVE" });
    expect(state.cartItems[1].stockStatus).toBeUndefined();
  });

  it("🔴 로그아웃하면 서버 장바구니의 사본은 지운다 — 다음 사람에게 보이지 않는다", () => {
    let state = reducer(undefined, syncedFromServer({ items: [item({ id: "1" })], ownerId: "42" }));
    state = reducer(state, forgetOwner());

    expect(state.cartItems).toEqual([]);
    expect(state.ownerId).toBeNull();
    expect(state.status).toBe(CartStatus.EMPTY);
  });

  it("브라우저에만 있는 장바구니(주인 없음)는 로그아웃으로 지우지 않는다", () => {
    const before = reducer(undefined, addToCart(item({ id: "local" })));
    expect(reducer(before, forgetOwner())).toEqual(before);
  });

  it("비우기(결제 뒤)는 주인을 지우지 않는다 — 같은 사람의 장바구니다", () => {
    let state = reducer(undefined, syncedFromServer({ items: [item({ id: "1" })], ownerId: "42" }));
    state = reducer(state, emptyCart());
    expect(state.ownerId).toBe("42");
  });
});
