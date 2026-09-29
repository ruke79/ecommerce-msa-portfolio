import { LOW_STOCK, cartLineStockOf, cartStockKey, stockStateOf } from "util/stock";

const sku = (...sizes) => ({ sizes });

describe("stockStateOf — 목록 카드의 재고 상태 (docs/59 §5)", () => {
    it("넉넉하면 아무 말도 하지 않는다", () => {
        expect(stockStateOf({}, sku({ quantity: 3 }, { quantity: 5 }))).toBeNull();
    });

    it(`${LOW_STOCK}개 이하면 '얼마 남음' — 사이즈를 합쳐 센다`, () => {
        expect(stockStateOf({}, sku({ quantity: 1 }, { quantity: 2 }))).toEqual({ kind: "low", count: 3 });
    });

    it("🔴 남은 게 없지만 누가 결제 중이면 품절이 아니라 '결제 중'", () => {
        expect(stockStateOf({}, sku({ quantity: 0, onHold: 1 }, { quantity: 0 }))).toEqual({ kind: "on_hold" });
    });

    it("남은 것도 잡힌 것도 없으면 품절", () => {
        expect(stockStateOf({}, sku({ quantity: 0 }, { quantity: 0 }))).toEqual({ kind: "sold_out" });
    });

    it("관리자가 품절로 내렸으면 수량과 상관없이 품절", () => {
        expect(stockStateOf({ status: "SOLD_OUT" }, sku({ quantity: 10 }))).toEqual({ kind: "sold_out" });
    });

    it("수량을 모르면(응답에 없다) 품절이라고 하지 않는다", () => {
        expect(stockStateOf({}, sku({ price: 10 }))).toBeNull();
        expect(stockStateOf({}, undefined)).toBeNull();
        expect(stockStateOf({}, sku())).toBeNull();
    });

    it("음수 수량은 0 으로 센다", () => {
        expect(stockStateOf({}, sku({ quantity: -2 }, { quantity: 1 }))).toEqual({ kind: "low", count: 1 });
    });
});


describe("장바구니 한 줄의 재고 (docs/59 §5)", () => {
    const line = (over) => ({ id: "1", color: { id: "5" }, size: "M", qty: 1, ...over });

    it("재고 키는 상품:색상:사이즈 — 모자라면 묻지 않는다", () => {
        expect(cartStockKey(line())).toBe("1:5:M");
        expect(cartStockKey(line({ color: undefined }))).toBeNull();
        expect(cartStockKey(line({ size: "" }))).toBeNull();
    });

    it("담은 수량이 남은 수량보다 많으면 short — 남은 수를 알려 준다", () => {
        expect(cartLineStockOf(line({ qty: 3, quantity: 2 }))).toEqual({ kind: "short", count: 2 });
    });

    it("🔴 남은 게 없지만 누가 결제 중이면 on_hold — 품절이 아니다", () => {
        expect(cartLineStockOf(line({ quantity: 0, onHold: 1 }))).toEqual({ kind: "on_hold" });
        expect(cartLineStockOf(line({ quantity: 0 }))).toEqual({ kind: "sold_out" });
    });

    it("관리자가 품절로 내렸으면 잡힌 것이 있어도 품절, 판매 종료·사라진 옵션은 discontinued", () => {
        expect(cartLineStockOf(line({ quantity: 0, onHold: 1, stockStatus: "SOLD_OUT" }))).toEqual({ kind: "sold_out" });
        expect(cartLineStockOf(line({ quantity: 3, stockStatus: "DELETED" }))).toEqual({ kind: "discontinued" });
        expect(cartLineStockOf(line({ stockStatus: "UNAVAILABLE" }))).toEqual({ kind: "discontinued" });
    });

    it("충분하거나 수량을 모르면 null", () => {
        expect(cartLineStockOf(line({ qty: 2, quantity: 2 }))).toBeNull();
        expect(cartLineStockOf(line())).toBeNull();
    });
});
