import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";

import Payment from "components/order/Payment";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

// PayPal 버튼은 그리지 않고 받은 콜백만 남긴다 — createOrder 를 직접 불러 본다.
let mockPaypalProps = null;
jest.mock("@paypal/react-paypal-js", () => ({
    PayPalButtons: (props) => { mockPaypalProps = props; return null; },
    usePayPalScriptReducer: () => [{ isPending: false, options: { currency: "USD" } }, jest.fn()],
}));
jest.mock("react-redux", () => ({ useDispatch: () => jest.fn() }));
jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ user: { email: "u@example.com" } }) }));
jest.mock("util/api", () => ({ postRequest: jest.fn() }));
jest.mock("util/portone", () => ({
    loadPortOne: jest.fn(), loadPortOneV2: jest.fn(), newPortOnePaymentId: () => "payment-1",
    portOneVersion: jest.fn(() => "v1"),
    portOneRedirectUrl: () => "https://shop.example/order/7",
    portOneCustomer: jest.requireActual("util/portone").portOneCustomer,
    portOneSupportsVirtualAccount: jest.requireActual("util/portone").portOneSupportsVirtualAccount,
    portOneStoreDetails: jest.requireActual("util/portone").portOneStoreDetails,
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
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/payment/stock-hold/track-1", {},
            "application/json", { headers: { "Payment-Attempt": expect.stringMatching(/^[A-Za-z0-9_-]{8,64}$/) } });
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

        fireEvent.click(screen.getByRole("button", { name: /신용카드로 결제하기/ }));

        expect(await screen.findByRole("alert")).toHaveTextContent("바지(L) 품절");
        expect(loadPortOne).not.toHaveBeenCalled();
        process.env.REACT_APP_PORTONE_IMP_CODE = prev;
    });

    it("포트원 버튼도 처음부터 눌 수 있다", () => {
        render(<Payment order={order("portone")} setOrder={jest.fn()} setLoading={jest.fn()} />);
        expect(screen.getByRole("button", { name: /신용카드로 결제하기/ })).toBeEnabled();
    });
});

/**
 * 🐛 2026-10-24 운영(KG이니시스 V2): "리디렉션 방식으로 결제창을 호출하려면 리디렉션 URL은 필수 입력입니다" 로 결제창이 열리지
 * 않았다. 모바일 등은 결제창을 페이지 이동으로 띄우므로 돌아올 주소가 있어야 하고, KG이니시스 PC 결제는 구매자 전화번호가 필수다.
 *
 * <p>[JA] KG イニシス V2 で「リダイレクト URL は必須」で決済画面が開かなかった。戻り先と購入者の電話番号を渡す。
 */
describe("주문 결제 화면 — 포트원 V2 결제창 요청", () => {
    it("🔴 돌아올 주소(이 주문 화면)와 배송지의 구매자 이름 · 전화번호를 함께 보낸다", async () => {
        const portone = require("util/portone");
        portone.portOneVersion.mockReturnValue("v2");
        const prev = process.env.REACT_APP_PORTONE_CHANNEL_KEY;
        process.env.REACT_APP_PORTONE_CHANNEL_KEY = "channel-key-test";
        const requestPayment = jest.fn().mockResolvedValue({ code: "FAILURE_TYPE_PG", message: "사용자가 결제를 취소하셨습니다" });
        portone.loadPortOneV2.mockResolvedValue({ requestPayment });
        postRequest.mockResolvedValue({ data: {} });
        try {
            render(<Payment order={{ ...order("portone"),
                    shippingAddress: { firstname: "山田", lastname: "太郎", phoneNumber: "010-1234-5678" } }}
                setOrder={jest.fn()} setLoading={jest.fn()} />);

            fireEvent.click(screen.getByRole("button", { name: /신용카드로 결제하기/ }));

            await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1));
            const req = requestPayment.mock.calls[0][0];
            expect(req.redirectUrl).toBe("https://shop.example/order/7");
            expect(req.forceRedirect).toBeUndefined();
            expect(req.storeDetails).toBeUndefined();   // 상점 정보는 JPY(일본결제)만
            // 🐛 이름이 언제나 비어 있었다 — 로그인 사용자 객체에는 이름이 없다(JWT). 배송지의 성 · 이름을 싣는다.
            expect(req.customer).toEqual({ email: "u@example.com", fullName: "山田 太郎", phoneNumber: "010-1234-5678" });
        } finally {
            portone.portOneVersion.mockReturnValue("v1");
            process.env.REACT_APP_PORTONE_CHANNEL_KEY = prev;
        }
    });
});

/**
 * 🐛 2026-10-24: 운영 채널이 이니시스 일본결제(JPY 만 · 가상계좌 없음)다 — '가상계좌' 를 고르면 결제창이 열리지 않는다.
 *
 * <p>[JA] 本番チャネルはイニシス日本決済(JPY のみ · バーチャル口座なし)— 「バーチャル口座」を選ぶと決済画面が開かない。
 */
describe("주문 결제 화면 — 포트원 결제 수단은 청구 통화를 따른다", () => {
    const jpy = { ...order("portone"), currency: "JPY", chargedAmount: 9000 };
    const krw = { ...order("portone"), currency: "KRW", chargedAmount: 81000 };

    it("🔴 JPY(이니시스 일본결제) — 가상계좌를 고를 수 없고 카드로 열며, 상점 정보를 싣는다", async () => {
        const portone = require("util/portone");
        portone.portOneVersion.mockReturnValue("v2");
        const prev = process.env.REACT_APP_PORTONE_CHANNEL_KEY;
        const prevEmail = process.env.REACT_APP_PORTONE_STORE_EMAIL;
        process.env.REACT_APP_PORTONE_CHANNEL_KEY = "channel-key-test";
        process.env.REACT_APP_PORTONE_STORE_EMAIL = "store@example.com";
        const requestPayment = jest.fn().mockResolvedValue({ code: "FAILURE_TYPE_PG", message: "x" });
        portone.loadPortOneV2.mockResolvedValue({ requestPayment });
        postRequest.mockResolvedValue({ data: {} });
        try {
            render(<Payment order={jpy} setOrder={jest.fn()} setLoading={jest.fn()} />);
            expect(screen.queryByRole("radio", { name: "가상계좌 (무통장입금)" })).not.toBeInTheDocument();

            fireEvent.click(screen.getByRole("button", { name: /신용카드로 결제하기/ }));
            await waitFor(() => expect(requestPayment).toHaveBeenCalledTimes(1));
            expect(requestPayment.mock.calls[0][0]).toEqual(expect.objectContaining({
                payMethod: "CARD", currency: "CURRENCY_JPY", totalAmount: 9000 }));
            expect(requestPayment.mock.calls[0][0].virtualAccount).toBeUndefined();
            // 🔴 이니시스 일본결제(JPPG)는 상점 정보가 필수 — 이메일은 빌드 변수에서.
            expect(requestPayment.mock.calls[0][0].storeDetails).toEqual(expect.objectContaining({
                storeName: "アマス", storeNameKana: "アマス", storeNameEn: "Amasu", phoneNumber: "0810700000001",
                email: "store@example.com", openingHours: { open: "00:00", close: "23:59" } }));
        } finally {
            portone.portOneVersion.mockReturnValue("v1");
            process.env.REACT_APP_PORTONE_CHANNEL_KEY = prev;
            process.env.REACT_APP_PORTONE_STORE_EMAIL = prevEmail;
        }
    });

    it("KRW(국내 채널) — 가상계좌를 고를 수 있다(예전 그대로)", () => {
        render(<Payment order={krw} setOrder={jest.fn()} setLoading={jest.fn()} />);
        expect(screen.getByRole("radio", { name: "가상계좌 (무통장입금)" })).toBeInTheDocument();
    });
});

/**
 * 🔴 PayPal 도 결제창을 열기 전에 결제 시작(재고 예약 갱신 · 쿠폰 잡기, coupon V7)을 거친다. 예전에는 PayPal 만 이 단계를 건너뛰어
 * 같은 쿠폰으로 다른 주문이 결제 중이어도 PayPal 창이 열렸다.
 */
describe("주문 결제 화면 — PayPal 결제 시작", () => {
    const actions = () => ({ order: { create: jest.fn().mockResolvedValue("PP-ORDER-1") } });

    it("결제 시작이 통과하면 PayPal 주문을 만든다", async () => {
        postRequest.mockResolvedValue({ data: { renewed: true } });
        render(<Payment order={order("paypal")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        const a = actions();
        await expect(mockPaypalProps.createOrder({}, a)).resolves.toBe("PP-ORDER-1");
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/payment/stock-hold/track-1", {},
            "application/json", { headers: { "Payment-Attempt": expect.stringMatching(/^[A-Za-z0-9_-]{8,64}$/) } });
        expect(a.order.create).toHaveBeenCalled();
    });

    it("🔴 거절되면(쿠폰이 다른 주문에서 결제 중) PayPal 주문을 만들지 않고 이유를 보여 준다", async () => {
        postRequest.mockRejectedValue({ response: { status: 400, data: {
            message: "이 쿠폰으로 결제가 진행 중인 다른 주문이 있습니다. 그 결제를 마치거나 취소한 뒤 다시 시도해 주세요." } } });
        render(<Payment order={order("paypal")} setOrder={jest.fn()} setLoading={jest.fn()} />);

        const a = actions();
        await expect(mockPaypalProps.createOrder({}, a)).rejects.toThrow();
        expect(a.order.create).not.toHaveBeenCalled();
        expect(await screen.findByRole("alert")).toHaveTextContent("결제가 진행 중인 다른 주문");
    });
});

/**
 * 🔴 결제 시작에 창마다의 시도 키를 싣는다(order V27) — 같은 탭에서 다시 누르면 같은 키(다시 열 수 있다), 다른 탭은 다른 키(서버가
 * 거절한다). 예전에는 두 탭에서 같은 주문을 둘 다 결제할 수 있었다.
 */
describe("주문 결제 화면 — 결제 시도 키", () => {
    const keyOf = (call) => call[3].headers["Payment-Attempt"];

    it("같은 탭에서 다시 시작하면 같은 키 — 탭(sessionStorage)에 남는다", async () => {
        postRequest.mockResolvedValue({ data: { renewed: true } });
        render(<Payment order={order("paypal")} setOrder={jest.fn()} setLoading={jest.fn()} />);
        const a = { order: { create: jest.fn().mockResolvedValue("PP-1") } };

        await mockPaypalProps.createOrder({}, a);
        await mockPaypalProps.createOrder({}, a);

        const calls = postRequest.mock.calls.filter((c) => c[0].includes("/payment/stock-hold/"));
        expect(calls).toHaveLength(2);
        expect(keyOf(calls[1])).toBe(keyOf(calls[0]));
        expect(window.sessionStorage.getItem("payment-attempt:track-1")).toBe(keyOf(calls[0]));
    });

    it("다른 탭(저장된 키가 없다)은 다른 키", () => {
        const { paymentAttemptKey } = jest.requireActual("util/paymentAttempt");
        window.sessionStorage.setItem("payment-attempt:track-2", "tab-one-key-123");
        expect(paymentAttemptKey("track-2")).toBe("tab-one-key-123");
        window.sessionStorage.removeItem("payment-attempt:track-2");
        expect(paymentAttemptKey("track-3")).not.toBe("tab-one-key-123");
    });
});

