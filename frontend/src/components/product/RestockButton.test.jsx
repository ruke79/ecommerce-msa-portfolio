import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";

import RestockButton, { sameColor } from "components/product/RestockButton";
import { fetchRestockSubscriptions, subscribeRestock, unsubscribeRestock } from "api/restockApi";
import { useAuthContext } from "store/AuthContext";

jest.mock("api/restockApi");
jest.mock("i18n/I18nProvider", () => ({ useTranslation: () => ({ t: (key) => key }) }));
jest.mock("store/AuthContext", () => ({ useAuthContext: jest.fn() }));
const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({ ...jest.requireActual("react-router-dom"), useNavigate: () => mockNavigate }));

const product = { id: "10", name: "티셔츠", slug: "tee" };

const renderButton = (props = {}) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    return render(
        <QueryClientProvider client={client}>
            <MemoryRouter>
                <RestockButton product={product} colorId="3" size="M" soldOut {...props} />
            </MemoryRouter>
        </QueryClientProvider>
    );
};

/**
 * 재입고 알림 받기 (docs/53) — 품절 옵션에서만 보이고, 누르면 걸고 다시 누르면 거둔다.
 */
describe("RestockButton", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        useAuthContext.mockReturnValue({ token: "t" });
        fetchRestockSubscriptions.mockResolvedValue([]);
        subscribeRestock.mockResolvedValue({ subscribed: true });
        unsubscribeRestock.mockResolvedValue(undefined);
    });

    it("품절이 아니면 아무것도 그리지 않는다", () => {
        const { container } = renderButton({ soldOut: false });
        expect(container).toBeEmptyDOMElement();
        expect(fetchRestockSubscriptions).not.toHaveBeenCalled();
    });

    it("🔴 품절 옵션에서 누르면 상품·색상·사이즈로 신청한다 — 사용자 값은 보내지 않는다", async () => {
        renderButton();
        fireEvent.click(await screen.findByRole("button", { name: "product.restock.subscribe" }));

        await waitFor(() => expect(subscribeRestock).toHaveBeenCalledWith(
            { productId: "10", colorId: "3", size: "M", productName: "티셔츠", productSlug: "tee" }));
    });

    it("이미 신청한 옵션이면 '신청됨' 이고, 누르면 거둔다", async () => {
        fetchRestockSubscriptions.mockResolvedValue([{ colorId: 3, size: "M" }]);
        renderButton();

        const button = await screen.findByRole("button", { pressed: true });
        fireEvent.click(button);

        await waitFor(() => expect(unsubscribeRestock).toHaveBeenCalledWith({ productId: "10", colorId: "3", size: "M" }));
        expect(subscribeRestock).not.toHaveBeenCalled();
    });

    it("로그인하지 않았으면 로그인으로 보낸다", async () => {
        useAuthContext.mockReturnValue({ token: null });
        renderButton();

        fireEvent.click(screen.getByRole("button"));

        expect(mockNavigate).toHaveBeenCalledWith("/signin");
        expect(subscribeRestock).not.toHaveBeenCalled();
    });

    it("색상 비교 — 없음(null·빈 값)끼리 같고, 숫자와 문자열 id 는 같다", () => {
        expect(sameColor(null, "")).toBe(true);
        expect(sameColor(undefined, null)).toBe(true);
        expect(sameColor(3, "3")).toBe(true);
        expect(sameColor(3, null)).toBe(false);
    });
});
