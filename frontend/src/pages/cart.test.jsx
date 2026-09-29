import { render, screen } from "@testing-library/react";

import Cart from "pages/cart";

const mockDispatch = jest.fn();
let mockCart;
let mockAuth;
let mockFetch;
jest.mock("react-redux", () => ({ useDispatch: () => mockDispatch, useSelector: (pick) => pick({ cart: mockCart }) }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => mockAuth }));
jest.mock("hook/hooks", () => ({ useFetchCart: () => mockFetch }));
jest.mock("pages/header/Header", () => () => null);
jest.mock("pages/header/MenuSidebar", () => () => null);
jest.mock("components/cart/CartPage", () => () => <div data-testid="cart-page" />);
jest.mock("components/cart/Empty", () => () => <div data-testid="cart-empty" />);
jest.mock("components/loader/Loading", () => () => null);
jest.mock("util/api", () => ({ __esModule: true, default: {}, getRequest: jest.fn(), loadCart: jest.fn() }));

const server = [{ _uid: "u9", id: "9" }];

beforeEach(() => {
    jest.clearAllMocks();
    mockAuth = { user: { userId: "42", email: "" } };
    mockFetch = { cartData: server, isSuccessCart: true, status: "success", isPendingCart: false };
});

/**
 * 장바구니 화면 — 로그인한 사용자의 장바구니는 **서버가 기준이다**(docs/40 0-b). 들어올 때마다 서버 것으로 바꾼다.
 */
describe("장바구니 화면 — 서버 것으로", () => {
    it("🔴 내 장바구니면 이미 손댔어도(UPDATED) 서버 것으로 바꾼다 — 다른 기기의 변경이 보인다", () => {
        mockCart = { cartItems: [{ _uid: "u1", id: "1" }], status: "updated", ownerId: "42" };

        render(<Cart />);

        expect(mockDispatch).toHaveBeenCalledWith(
            expect.objectContaining({ type: "cart/syncedFromServer", payload: { items: server, ownerId: "42" } }));
    });

    it("아직 내 것으로 맞춰지지 않은 장바구니(주인 없음·다른 주인)는 덮지 않는다 — CartSync 가 올린 뒤 채운다", () => {
        for (const ownerId of [null, "41"]) {
            mockCart = { cartItems: [{ _uid: "u1", id: "1" }], status: "updated", ownerId };
            render(<Cart />);
        }

        expect(mockDispatch).not.toHaveBeenCalled();
    });

    it("🔴 서버를 못 읽으면(503) 가진 장바구니를 그대로 그린다 — 비우지도, 빈 화면도 아니다", () => {
        mockCart = { cartItems: [{ _uid: "u1", id: "1" }], status: "updated", ownerId: "42" };
        mockFetch = { cartData: undefined, isSuccessCart: false, status: "error", isPendingCart: false };

        render(<Cart />);

        expect(screen.getByTestId("cart-page")).toBeInTheDocument();
        expect(mockDispatch).not.toHaveBeenCalled();
    });
});
