import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import Product from "components/cart/Product";
import { withI18n } from "i18n/testing";
import { removeCartItem, setCartItemQty } from "util/api";

const mockDispatch = jest.fn();
let mockAuth;
jest.mock("react-redux", () => ({ useDispatch: () => mockDispatch }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => mockAuth }));
jest.mock("hook/useMoney", () => ({ useMoney: () => (v) => String(v) }));
jest.mock("util/api", () => ({ __esModule: true, default: {}, setCartItemQty: jest.fn(), removeCartItem: jest.fn() }));

const line = (over = {}) => ({
    _uid: "u1", id: "1", name: "상품1", color: { id: "5", colorImage: "" }, size: "M",
    images: [""], price: 10, priceBefore: 10, shipping: 0, qty: 2, quantity: 9, ...over,
});

const renderLine = ({ ownerId = "42", selected = [], setSelected = jest.fn() } = {}) => {
    const product = line();
    render(withI18n(
        <MemoryRouter>
            <Product product={product} selected={selected} setSelected={setSelected}
                cart={{ cartItems: [product, line({ _uid: "u2", id: "2" })], ownerId }} />
        </MemoryRouter>
    ));
    return { setSelected };
};

const plus = () => screen.getAllByRole("button")[1];

beforeEach(() => {
    jest.clearAllMocks();
    mockAuth = { token: "t", user: { userId: "42" } };
});

/**
 * 장바구니 줄의 수량·삭제는 로그인한 사용자면 **서버에 바로** 반영된다(docs/40 0-b). 예전에는 브라우저에만 반영돼 다른 기기에서는
 * 옛 장바구니가 보였고, 체크아웃에서 고르지 않은 줄은 서버에서 사라졌다.
 */
describe("장바구니 줄 — 서버가 기준", () => {
    it("🔴 수량을 올리면 서버에 상품·색상·사이즈로 줄을 가리켜 보내고, 응답(지금 장바구니)으로 바꾼다", async () => {
        const now = [line({ qty: 3 })];
        setCartItemQty.mockResolvedValue(now);

        renderLine();
        fireEvent.click(plus());

        await waitFor(() => expect(setCartItemQty).toHaveBeenCalledWith({ productId: "1", colorId: "5", size: "M", qty: 3 }));
        await waitFor(() => expect(mockDispatch).toHaveBeenCalledWith(
            expect.objectContaining({ type: "cart/syncedFromServer", payload: { items: now, ownerId: "42" } })));
    });

    it("🔴 빼기도 서버에 — 고른 목록에서도 뺀다", async () => {
        removeCartItem.mockResolvedValue(null);
        const { setSelected } = renderLine({ selected: [line()] });

        fireEvent.click(screen.getByTestId("cart-line-remove"));

        await waitFor(() => expect(removeCartItem).toHaveBeenCalledWith({ productId: "1", colorId: "5", size: "M" }));
        await waitFor(() => expect(setSelected).toHaveBeenCalledWith([]));
        expect(mockDispatch).toHaveBeenCalledWith(expect.objectContaining({ type: "cart/syncedFromServer" }));
    });

    it("서버가 거절하면 화면을 바꾸지 않고 그 이유를 보여 준다", async () => {
        removeCartItem.mockRejectedValue({ response: { data: { message: "잠시 후 다시" } } });
        const { setSelected } = renderLine({ selected: [line()] });

        fireEvent.click(screen.getByTestId("cart-line-remove"));

        expect(await screen.findByTestId("cart-line-error")).toHaveTextContent("잠시 후 다시");
        expect(mockDispatch).not.toHaveBeenCalled();
        expect(setSelected).not.toHaveBeenCalled();
    });

    it("로그인 전(주인 없는 장바구니)은 브라우저에서만 바꾼다", async () => {
        mockAuth = { token: null, user: {} };
        renderLine({ ownerId: null });

        fireEvent.click(plus());

        await waitFor(() => expect(mockDispatch).toHaveBeenCalledWith(expect.objectContaining({ type: "cart/updateCart" })));
        expect(setCartItemQty).not.toHaveBeenCalled();
        const [{ payload }] = mockDispatch.mock.calls[0];
        expect(payload.map((p) => p.qty)).toEqual([3, 2]);
    });

    it("로그인했어도 아직 내 것으로 맞춰지지 않은 장바구니(다른 주인)는 서버를 부르지 않는다", async () => {
        renderLine({ ownerId: "41" });

        fireEvent.click(plus());

        await waitFor(() => expect(mockDispatch).toHaveBeenCalled());
        expect(setCartItemQty).not.toHaveBeenCalled();
    });
});
