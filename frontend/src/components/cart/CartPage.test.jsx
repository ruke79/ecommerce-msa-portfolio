import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";

import CartPage from "components/cart/CartPage";
import { withI18n } from "i18n/testing";
import { getCartStock, saveCart } from "util/api";

const mockDispatch = jest.fn();
const mockNavigate = jest.fn();
jest.mock("react-redux", () => ({ useDispatch: () => mockDispatch }));
jest.mock("react-router-dom", () => ({ ...jest.requireActual("react-router-dom"), useNavigate: () => mockNavigate }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ token: "t" }) }));
jest.mock("util/api", () => ({
    __esModule: true, default: {}, getCartStock: jest.fn(), saveCart: jest.fn(), putRequest: jest.fn(), getRequest: jest.fn(),
}));

const line = (over) => ({
    _uid: `u-${over.id}`, id: over.id, name: `상품${over.id}`, color: { id: "5", colorImage: "" }, size: "M",
    images: [""], price: 10, priceBefore: 10, shipping: 0, qty: 1, quantity: 5, ...over,
});

const renderPage = (items, lang = "ko") => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(withI18n(
        <QueryClientProvider client={client}>
            <MemoryRouter>
                <CartPage cart={{ cartItems: items, status: "updated" }} />
            </MemoryRouter>
        </QueryClientProvider>,
        lang
    ));
};

beforeEach(() => jest.clearAllMocks());

/**
 * 장바구니 화면은 들어올 때 담아 둔 항목의 **지금** 재고를 새로 본다 (docs/59 §5).
 */
describe("장바구니 — 지금 재고", () => {
    it("🔴 들어오면 항목들의 재고를 한 번에 묻고, 받은 값으로 장바구니를 갱신한다", async () => {
        const stock = { "1:5:M": { quantity: 0, onHold: 1, status: "ACTIVE" } };
        getCartStock.mockResolvedValue(stock);

        renderPage([line({ id: "1" }), line({ id: "2", size: "L" })]);

        await waitFor(() => expect(getCartStock).toHaveBeenCalledWith(["1:5:M", "2:5:L"]));
        await waitFor(() => expect(mockDispatch).toHaveBeenCalledWith(
            expect.objectContaining({ type: "cart/syncStock", payload: { stock, asked: ["1:5:M", "2:5:L"] } })));
    });

    it("재고를 읽지 못하면(503) 장바구니를 건드리지 않는다", async () => {
        getCartStock.mockRejectedValue({ response: { status: 503 } });

        renderPage([line({ id: "1" })]);

        await waitFor(() => expect(getCartStock).toHaveBeenCalled());
        expect(mockDispatch).not.toHaveBeenCalledWith(expect.objectContaining({ type: "cart/syncStock" }));
    });

    it("항목마다 문제를 알린다 — 수량 초과는 남은 수, 결제 중은 품절과 다르게", () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        renderPage([line({ id: "1", qty: 3, quantity: 2 }), line({ id: "2", quantity: 0, onHold: 1 })]);

        const notes = screen.getAllByTestId("cart-line-stock");
        expect(notes[0]).toHaveTextContent("남은 수량이 2개입니다");
        expect(notes[1]).toHaveTextContent("다른 고객이 결제 중인 재고입니다");
    });

    it("🔴 고른 항목 중 지금 재고로 살 수 없는 것이 있으면 주문서로 넘어가지 않는다", async () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        renderPage([line({ id: "1", qty: 3, quantity: 2 })]);

        fireEvent.click(screen.getAllByRole("checkbox")[0]);          // 전체 선택
        fireEvent.click(screen.getByRole("button", { name: "주문서로 이동" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("지금 재고로 주문할 수 없는 상품이 있습니다: 상품1");
        expect(saveCart).not.toHaveBeenCalled();
        expect(mockNavigate).not.toHaveBeenCalled();
    });

    it("문제가 없으면 저장하고 넘어간다", async () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        saveCart.mockResolvedValue({ cartId: "c1" });
        renderPage([line({ id: "1", qty: 2, quantity: 2 })]);

        fireEvent.click(screen.getAllByRole("checkbox")[0]);
        fireEvent.click(screen.getByRole("button", { name: "주문서로 이동" }));

        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/checkout"));
    });
});

/** 🔴 제목("Shopping Cart")과 저장 실패 문구가 박혀 있었다 — 일본어로 그려 봐야 드러난다. */
describe("장바구니 — 일본어", () => {
    it("제목이 일본어다", () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        renderPage([line({ id: "1" })], "ja");

        expect(screen.getByText("ショッピングカート")).toBeInTheDocument();
        expect(screen.queryByText("Shopping Cart")).not.toBeInTheDocument();
    });

    it("저장에 실패했는데 서버가 이유를 주지 않으면 우리 문구를 일본어로 보여 준다", async () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        saveCart.mockRejectedValue({ response: { status: 500 } });
        renderPage([line({ id: "1" })], "ja");

        fireEvent.click(screen.getAllByRole("checkbox")[0]);
        fireEvent.click(screen.getByRole("button", { name: "レジに進む" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("カートを保存できませんでした。もう一度お試しください。");
    });

    it("한국어 저장 실패 문구는 그대로다", async () => {
        getCartStock.mockReturnValue(new Promise(() => {}));
        saveCart.mockRejectedValue({ response: { status: 500 } });
        renderPage([line({ id: "1" })]);

        fireEvent.click(screen.getAllByRole("checkbox")[0]);
        fireEvent.click(screen.getByRole("button", { name: "주문서로 이동" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("장바구니를 저장하지 못했습니다. 다시 시도해 주세요.");
    });
});
