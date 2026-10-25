import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import Infos from "components/product/Infos";
import { withI18n } from "i18n/testing";
import { getRequest, putCartItem } from "util/api";

const mockDispatch = jest.fn();
let mockAuth = { token: "t" };
jest.mock("util/api", () => ({ __esModule: true, default: {}, getRequest: jest.fn(), putRequest: jest.fn(), putCartItem: jest.fn() }));
jest.mock("react-redux", () => ({ useDispatch: () => mockDispatch, useSelector: (pick) => pick({ cart: { cartItems: [] } }) }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => mockAuth }));
jest.mock("hook/useMoney", () => ({ useMoney: () => (v) => String(v) }));
jest.mock("components/product/AccordianProduct", () => () => null);
// 재입고 알림 버튼은 따로 테스트한다 — 여기서는 "품절로 보았는가" 만 본다.
jest.mock("components/product/RestockButton", () => ({ soldOut }) => (
    <span data-testid="restock">{soldOut ? "restock-offered" : "no-restock"}</span>
));

const product = (overrides = {}) => ({
    id: "1", name: "티셔츠", slug: "tee", style: 0, rating: 0, discount: 0, description: "",
    price: 10, priceBefore: 10, priceRange: { min: 10, max: 10 },
    colors: [{ id: "5", color: "black", colorImage: "" }],
    sizes: [{ size: "M", quantity: 0, onHold: 1 }],
    quantity: 0, onHold: 1,
    ...overrides,
});

const renderAt = (p, url = "/product/tee?style=0&size=0") => {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    return render(withI18n(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={[url]}>
                <Infos product={p} num_reviews={0} setActiveImg={jest.fn()} />
            </MemoryRouter>
        </QueryClientProvider>
    ));
};

beforeEach(() => {
    jest.clearAllMocks();
    mockAuth = { token: "t" };
});

/**
 * 결제 전 재고 예약이 상품 화면에 보이는 방식 (docs/59 §5) — 서버가 남은 수량에서 다른 주문의 예약을 이미 뺐다.
 * 남은 게 0 이어도 누가 결제 중인 것이면 품절이 아니다.
 */
describe("상품 정보 — 결제 중인 재고", () => {
    it("남은 게 0 이고 다른 손님이 결제 중이면 '결제 중' 안내를 보여 주고, 재입고 알림은 권하지 않는다", () => {
        renderAt(product());

        expect(screen.getByTestId("stock-on-hold")).toHaveTextContent("다른 고객이 결제 중인 재고입니다");
        // 재입고 알림은 실물이 0 을 건널 때만 간다 — 예약이 풀려 돌아오는 것으로는 오지 않는다.
        expect(screen.getByTestId("restock")).toHaveTextContent("no-restock");
    });

    it("진짜 품절(예약도 없음)이면 안내 대신 재입고 알림을 권한다", () => {
        renderAt(product({ sizes: [{ size: "M", quantity: 0 }], onHold: 0 }));

        expect(screen.queryByTestId("stock-on-hold")).toBeNull();
        expect(screen.getByTestId("restock")).toHaveTextContent("restock-offered");
    });

    it("사이즈를 고르기 전에는 안내하지 않는다", () => {
        renderAt(product(), "/product/tee?style=0");
        expect(screen.queryByTestId("stock-on-hold")).toBeNull();
    });

    it("담는 순간 다 잡혀 버렸으면 '품절' 이 아니라 '결제 중' 이라고 말한다", async () => {
        // 화면을 연 뒤에 다른 손님이 잡았다 — 화면은 1개 남음, 담기 조회는 0개·잡힘 1.
        getRequest.mockResolvedValue({ data: { id: "1", size: "M", quantity: 0, onHold: 1 } });
        renderAt(product({ sizes: [{ size: "M", quantity: 1 }], quantity: 1, onHold: 0 }));

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(screen.getByText(/다른 고객이 결제 중인 재고입니다/)).toBeInTheDocument());
        expect(screen.queryByText("품절된 상품입니다.")).toBeNull();
    });

    it("0 개면 '재고보다 많은 수량' 이 아니라 품절이라고 말한다 — 검사 순서", async () => {
        getRequest.mockResolvedValue({ data: { id: "1", size: "M", quantity: 0 } });
        renderAt(product({ sizes: [{ size: "M", quantity: 1 }], quantity: 1, onHold: 0 }));

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(screen.getByText("품절된 상품입니다.")).toBeInTheDocument());
    });
});

/**
 * 사이즈 없는 상품(식품 등) — 사이즈 행은 있지만 이름이 비어 있다. 예전에는 빈 동그라미가 줄지어 나오고, 고르지 않으면
 * "사이즈를 선택하세요" 로 담기가 막혔다.
 */
describe("상품 정보 — 사이즈 없는 상품", () => {
    const oil = (overrides = {}) => product({
        name: "Italian Olive Oil", sizeless: true,
        sizes: [{ size: "", quantity: 14 }, { size: "", quantity: 3 }],
        quantity: 14, onHold: 0, ...overrides,
    });

    it("사이즈 고르기를 그리지 않는다", () => {
        renderAt(oil(), "/product/oil?style=0");
        expect(screen.queryByTestId("size-picker")).toBeNull();
    });

    it("🔴 고르지 않아도 첫 행(0)으로 담는다 — 사이즈를 고르라고 막지 않는다", async () => {
        getRequest.mockResolvedValue({ data: { id: "1", size: "", quantity: 14 } });
        renderAt(oil(), "/product/oil?style=0");

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(getRequest).toHaveBeenCalledWith("/catalog-service/api/cart/1?style=0&size=0"));
        expect(screen.queryByText(/사이즈를 선택/)).toBeNull();
    });

    it("남은 수량은 그 행의 수량이다 — 이름 없는 행들을 더하지 않는다", () => {
        renderAt(oil(), "/product/oil?style=0");
        expect(screen.getByText(/14/)).toBeInTheDocument();
        expect(screen.queryByText(/17/)).toBeNull();
    });

    it("사이즈가 있는 상품은 그대로 고르게 한다", () => {
        renderAt(product({ sizes: [{ size: "M", quantity: 1 }], quantity: 1, onHold: 0 }), "/product/tee?style=0");
        expect(screen.getByTestId("size-picker")).toBeInTheDocument();
    });
});

/** 관리자가 품절로 내린 상품 — 수량이 남아 있어도 담기지 않는다. */
describe("상품 정보 — 관리자가 품절로 내림", () => {
    it("🔴 담기 버튼이 막히고 품절이라고 말한다(수량이 남아 있어도)", () => {
        renderAt(product({ status: "SOLD_OUT", sizes: [{ size: "M", quantity: 5 }], quantity: 5, onHold: 0 }));
        expect(screen.getByTestId("discontinued")).toHaveTextContent("품절된 상품입니다.");
        expect(screen.getByRole("button", { name: /장바구니|카트|cart/i })).toBeDisabled();
    });

    it("🔴 서버가 빈 본문을 주면 담지 않는다 — 예전에는 수량 비교가 모두 거짓이라 담겼다", async () => {
        getRequest.mockResolvedValue({ data: "" });
        renderAt(product({ sizes: [{ size: "M", quantity: 1 }], quantity: 1, onHold: 0 }));

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(screen.getByText("품절된 상품입니다.")).toBeInTheDocument());
    });

    it("서버가 거절하면(409) 화면이 죽지 않고 그 이유를 보여 준다", async () => {
        getRequest.mockRejectedValue({ response: { status: 409, data: { message: "품절되었거나 판매하지 않는 상품입니다." } } });
        renderAt(product({ sizes: [{ size: "M", quantity: 1 }], quantity: 1, onHold: 0 }));

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(screen.getByText("품절되었거나 판매하지 않는 상품입니다.")).toBeInTheDocument());
    });
});

/**
 * 로그인했으면 담기가 서버에 바로 반영된다(docs/40 0-b) — 서버가 장바구니의 기준이다. 다른 기기에서도 같은 장바구니가 보인다.
 */
describe("상품 정보 — 서버 장바구니에 담기", () => {
    const inStock = () => product({ sizes: [{ size: "M", quantity: 5 }], quantity: 5, onHold: 0 });
    const catalogAnswer = { id: "1", style: 0, size: "M", quantity: 5, color: { id: "5" } };

    it("🔴 로그인했으면 catalog 의 담기 응답 + 수량·줄 id 를 서버에 담고, 응답(지금 장바구니)으로 바꾼다", async () => {
        mockAuth = { token: "t", user: { userId: "42" } };
        getRequest.mockResolvedValue({ data: catalogAnswer });
        putCartItem.mockResolvedValue([{ ...catalogAnswer, qty: 1 }]);
        renderAt(inStock());

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(putCartItem).toHaveBeenCalledWith(
            expect.objectContaining({ id: "1", size: "M", qty: 1, _uid: "1_0_0" })));
        await waitFor(() => expect(mockDispatch).toHaveBeenCalledWith(expect.objectContaining({
            type: "cart/syncedFromServer", payload: { items: [{ ...catalogAnswer, qty: 1 }], ownerId: "42" } })));
        expect(mockDispatch).not.toHaveBeenCalledWith(expect.objectContaining({ type: "cart/addToCart" }));
    });

    it("서버가 거절하면 그 이유를 보여 주고 장바구니를 바꾸지 않는다", async () => {
        mockAuth = { token: "t", user: { userId: "42" } };
        getRequest.mockResolvedValue({ data: catalogAnswer });
        putCartItem.mockRejectedValue({ response: { data: { message: "상품을 찾을 수 없습니다." } } });
        renderAt(inStock());

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(screen.getByText("상품을 찾을 수 없습니다.")).toBeInTheDocument());
        expect(mockDispatch).not.toHaveBeenCalled();
    });

    it("로그인 전이면 브라우저에만 담는다 — 로그인하면 CartSync 가 올린다", async () => {
        mockAuth = { token: null, user: {} };
        getRequest.mockResolvedValue({ data: catalogAnswer });
        renderAt(inStock());

        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        await waitFor(() => expect(mockDispatch).toHaveBeenCalledWith(expect.objectContaining({ type: "cart/addToCart" })));
        expect(putCartItem).not.toHaveBeenCalled();
    });
});

describe("상품 정보 — 상품 번호(SKU)", () => {
    it("고른 사이즈의 SKU 를 보여 준다 — 문의 · 반품 때 '어느 것' 인지 말할 수 있게", () => {
        renderAt(product({ sku: "RUN-BLK-280" }));
        expect(screen.getByTestId("product-sku")).toHaveTextContent("상품 번호 RUN-BLK-280");
    });

    it("SKU 가 없으면 그 줄을 그리지 않는다", () => {
        renderAt(product());
        expect(screen.queryByTestId("product-sku")).not.toBeInTheDocument();
    });
});

/**
 * 🐛 2026-10-25 운영: 사이즈를 고르고 색상을 누르면 "사이즈를 선택해 주세요" 가 다시 나왔다 — 색상 링크가 `size` 를 버렸다.
 * 새 색상에도 같은 이름의 사이즈가 있으면 그것을 실어 간다(번호는 색상마다 다를 수 있다).
 *
 * <p>[JA] サイズを選んで色を押すと「サイズを選択してください」が再び出た — 色のリンクが size を捨てていた。
 */
describe("상품 정보 — 색상을 바꿔도 고른 사이즈는 그대로", () => {
    const twoColors = () => product({
        colors: [{ id: "5", color: "black", colorImage: "b.png" }, { id: "6", color: "white", colorImage: "w.png" }],
        skus: [
            { images: ["b1"], sizes: [{ size: "S" }, { size: "M" }, { size: "L" }] },
            { images: ["w1"], sizes: [{ size: "M" }, { size: "L" }] },   // 흰색에는 S 가 없다 — M 은 0 번
        ],
        sizes: [{ size: "S", quantity: 3 }, { size: "M", quantity: 3 }, { size: "L", quantity: 3 }],
        quantity: 3, onHold: 0,
    });
    const colorLink = (alt) => screen.getByAltText(alt).closest("a").getAttribute("href");

    it("🔴 검정 M(1번)을 고른 뒤 흰색 링크는 흰색의 M(0번)을 싣는다", () => {
        renderAt(twoColors(), "/product/tee?style=0&size=1");
        expect(colorLink("white")).toBe("/product/tee/?style=1&size=0");
        expect(colorLink("black")).toBe("/product/tee/?style=0&size=1");
    });

    it("새 색상에 없는 사이즈(S)면 싣지 않는다 — 손님이 다시 고른다", () => {
        renderAt(twoColors(), "/product/tee?style=0&size=0");
        expect(colorLink("white")).toBe("/product/tee/?style=1");
    });

    it("🔴 색상을 누른 뒤 담기 — '사이즈를 먼저 선택해 주세요' 가 나오지 않는다", async () => {
        getRequest.mockResolvedValue({ data: { id: "1", size: "M", quantity: 3, onHold: 0 } });
        const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
        render(withI18n(
            <QueryClientProvider client={client}>
                <MemoryRouter initialEntries={["/product/tee?style=0&size=1"]}>
                    <Routes>
                        <Route path="/product/:slug" element={<Infos product={twoColors()} num_reviews={0} setActiveImg={jest.fn()} />} />
                    </Routes>
                </MemoryRouter>
            </QueryClientProvider>
        ));

        fireEvent.click(screen.getByAltText("white"));
        fireEvent.click(screen.getByRole("button", { name: /장바구니|카트|cart/i }));

        // 사이즈 확인을 지나 담기 조회까지 갔다(예전에는 여기서 멈추고 오류를 띄웠다). 스타일 번호는 loader 가 다시 읽은 상품의 것이라
        // 이 테스트(상품 고정)에서는 보지 않는다 — 사이즈는 주소가 실어 온 흰색의 0 번.
        await waitFor(() => expect(getRequest).toHaveBeenCalledWith(expect.stringMatching(/&size=0$/)));
        expect(screen.queryByText("사이즈를 먼저 선택해 주세요.")).toBeNull();
    });

    it("사이즈를 고르기 전이면 색상만", () => {
        renderAt(twoColors(), "/product/tee?style=0");
        expect(colorLink("white")).toBe("/product/tee/?style=1");
    });
});
