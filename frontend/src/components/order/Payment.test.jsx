import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";

import Payment from "components/order/Payment";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("@paypal/react-paypal-js", () => ({
    PayPalButtons: () => null,
    usePayPalScriptReducer: () => [{ isPending: false, options: { currency: "USD" } }, jest.fn()],
}));
jest.mock("react-redux", () => ({ useDispatch: () => jest.fn() }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ user: { email: "u@example.com" } }) }));
jest.mock("util/api", () => ({ postRequest: jest.fn() }));
jest.mock("util/portone", () => ({
    loadPortOne: jest.fn(), loadPortOneV2: jest.fn(), newPortOnePaymentId: () => "payment-1",
    portOneVersion: () => "v1",
}));
jest.mock("components/order/BankTransfer", () => () => null);

const { postRequest } = require("util/api");

const order = (paymentMethod) => ({
    orderId: 7, trackingId: "track-1", total: 59.98, orderStatus: "NOT_PROCESSED", paymentMethod,
    orderedProducts: [],
});

let assign;
beforeAll(() => {
    // jsdom 의 location 은 실제로 이동하려 든다 — 이동 대신 기록한다.
    const original = window.location;
    delete window.location;
    window.location = { ...original, assign: jest.fn(), reload: jest.fn() };
    assign = window.location.assign;
});
beforeEach(() => jest.clearAllMocks());

/**
 * 🐛 결제 상태가 {@code loading: true} 로 시작해 되돌아오지 않았다. Stripe·포트원 버튼은
 * 그 값으로 비활성화되므로 <b>처음부터 "이동 중…" 인 채 눌리지 않았다.</b> PayPal 은 SDK
 * 의 isPending 을 보므로 멀쩡했고, 그래서 오래 묻혀 있었다.
 */
describe("주문 결제 화면 — Stripe · 포트원 버튼", () => {
    it("Stripe 버튼은 처음부터 눌 수 있고, 누르면 세션을 만들어 그 주소로 이동한다", async () => {
        postRequest.mockResolvedValue({ data: { url: "https://checkout.stripe.com/c/pay/cs_test_1", sessionId: "cs_test_1" } });
        render(<Payment order={order("stripe")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        const button = screen.getByRole("button", { name: "카드로 결제하기 (Stripe)" });
        expect(button).toBeEnabled();

        fireEvent.click(button);
        expect(screen.getByRole("button", { name: "결제 페이지로 이동 중…" })).toBeDisabled();

        await waitFor(() => expect(postRequest).toHaveBeenCalledWith(
            "/order-service/api/order/payment/stripe/session", { trackingId: "track-1" }));
        await waitFor(() => expect(assign).toHaveBeenCalledWith("https://checkout.stripe.com/c/pay/cs_test_1"));
    });

    /** 실패는 보여야 한다 — 버튼이 돌아오고 서버의 이유가 그 아래 뜬다. */
    it("세션 생성이 거절되면 이유를 보여 주고 버튼을 되돌린다", async () => {
        postRequest.mockRejectedValue({ response: { data: { message: "Stripe 결제가 설정되지 않았습니다." } } });
        render(<Payment order={order("stripe")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        fireEvent.click(screen.getByRole("button", { name: "카드로 결제하기 (Stripe)" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("Stripe 결제가 설정되지 않았습니다.");
        expect(screen.getByRole("button", { name: "카드로 결제하기 (Stripe)" })).toBeEnabled();
        expect(assign).not.toHaveBeenCalled();
    });

    /** 🔴 결제창을 열기 직전 예약을 새로 잡는다(docs/59 §8) — 모자라면 결제창(세션)을 열지 않고 이유를 보여 준다. */
    it("🔴 재고가 모자라면 Stripe 세션을 만들지 않고 이유를 보여 준다", async () => {
        postRequest.mockImplementation((url) => url.includes("/payment/stock-hold/")
            ? Promise.reject({ response: { status: 400, data: { message: "재고가 부족해 결제를 시작하지 못했습니다: 티셔츠(M) 품절. 장바구니에서 수량을 조정해 주세요." } } })
            : Promise.resolve({ data: { url: "https://checkout.stripe.com/x" } }));
        render(<Payment order={order("stripe")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        fireEvent.click(screen.getByRole("button", { name: "카드로 결제하기 (Stripe)" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("티셔츠(M) 품절");
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/payment/stock-hold/track-1", {});
        expect(postRequest).not.toHaveBeenCalledWith("/order-service/api/order/payment/stripe/session", expect.anything());
        expect(assign).not.toHaveBeenCalled();
    });

    it("예약 갱신이 네트워크 오류면 막지 않는다(fail-open) — 세션으로 넘어간다", async () => {
        postRequest.mockImplementation((url) => url.includes("/payment/stock-hold/")
            ? Promise.reject(new Error("Network Error"))
            : Promise.resolve({ data: { url: "https://checkout.stripe.com/y" } }));
        render(<Payment order={order("stripe")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        fireEvent.click(screen.getByRole("button", { name: "카드로 결제하기 (Stripe)" }));

        await waitFor(() => expect(assign).toHaveBeenCalledWith("https://checkout.stripe.com/y"));
    });

    it("🔴 포트원도 — 모자라면 결제창 스크립트를 불러오지 않는다", async () => {
        const { loadPortOne } = require("util/portone");
        const prev = process.env.REACT_APP_PORTONE_IMP_CODE;
        process.env.REACT_APP_PORTONE_IMP_CODE = "imp00000000";
        postRequest.mockRejectedValue({ response: { status: 400, data: { message: "재고가 부족해 결제를 시작하지 못했습니다: 바지(L) 품절. 장바구니에서 수량을 조정해 주세요." } } });
        render(<Payment order={order("portone")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        fireEvent.click(screen.getByRole("button", { name: /국내 결제/ }));

        expect(await screen.findByRole("alert")).toHaveTextContent("바지(L) 품절");
        expect(loadPortOne).not.toHaveBeenCalled();
        process.env.REACT_APP_PORTONE_IMP_CODE = prev;
    });

    it("포트원 버튼도 처음부터 눌 수 있다", () => {
        render(<Payment order={order("portone")} setOrder={jest.fn()} setLoading={jest.fn()} />);
        expect(screen.getByRole("button", { name: /국내 결제/ })).toBeEnabled();
    });
});
