import { cartIsThisOrder } from "./cartOrder";

describe("장바구니가 이 주문 그대로인가", () => {
    const order = [{ name: "Italian Olive Oil", qty: 1 }, { name: "티셔츠", qty: 2 }];

    test("🔴 담긴 줄이 전부 주문에 있으면 그렇다 — 결제 페이지에서 돌아오지 못해도 비운다", () => {
        expect(cartIsThisOrder([{ name: "Italian Olive Oil", qty: 1 }, { name: "티셔츠", qty: 2 }], order)).toBe(true);
    });

    test("주문에 없는 줄이 하나라도 있으면 아니다 — 옛 주문을 보다가 새 장바구니를 지우지 않는다", () => {
        expect(cartIsThisOrder([{ name: "바지", qty: 1 }], order)).toBe(false);
        expect(cartIsThisOrder([{ name: "Italian Olive Oil", qty: 3 }], order)).toBe(false);
    });

    test("같은 품목을 두 번 셈하지 않는다", () => {
        expect(cartIsThisOrder([{ name: "티셔츠", qty: 2 }, { name: "티셔츠", qty: 2 }], order)).toBe(false);
    });

    test("빈 장바구니·주문 없음은 아니다", () => {
        expect(cartIsThisOrder([], order)).toBe(false);
        expect(cartIsThisOrder([{ name: "a", qty: 1 }], undefined)).toBe(false);
    });
});
