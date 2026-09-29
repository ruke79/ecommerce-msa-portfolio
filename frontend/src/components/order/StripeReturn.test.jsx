import { useState } from "react";
import { render as rtlRender, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import StripeReturn from "components/order/StripeReturn";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({ postRequest: jest.fn() }));
const { postRequest } = require("util/api");

const order = { trackingId: "track-1" };

/**
 * 주문 페이지의 모양을 흉내 낸다 — onPaid 가 화면을 "다시 뜨게" 한다(예전에는
 * window.location.reload 였다). key 를 바꿔 StripeReturn 을 다시 마운트하면, 그 순간의
 * 주소에 아직 ?stripe=success 가 남아 있는지가 그대로 드러난다.
 */
function Page() {
    const [gen, setGen] = useState(0);
    const location = useLocation();
    return (
        <>
            <span data-testid="search">{location.search}</span>
            <span data-testid="gen">{gen}</span>
            <StripeReturn key={gen} order={order} onPaid={() => setGen((g) => g + 1)} />
        </>
    );
}

const renderAt = (url) => render(
    <MemoryRouter initialEntries={[url]}>
        <Routes><Route path="/order/:id" element={<Page />} /></Routes>
    </MemoryRouter>,
);

beforeEach(() => jest.clearAllMocks());

/**
 * 🐛 "결제를 확인하는 중 → 결제가 완료되었습니다 → 새로고침" 이 무한히 반복됐다.
 * 쿼리를 finally 에서 지웠는데 then 의 onPaid(= reload)가 먼저 실행돼, 세션 id 가 남은
 * 주소로 다시 떴기 때문이다.
 */
describe("StripeReturn — 복귀 확인", () => {
    it("확인 요청은 세션 id 하나만 보내고, 성공하면 완료를 보여 준다", async () => {
        postRequest.mockResolvedValue({ data: {} });
        const onPaid = jest.fn();
        render(
            <MemoryRouter initialEntries={["/order/7?stripe=success&session_id=cs_test_1"]}>
                <Routes><Route path="/order/:id" element={<StripeReturn order={order} onPaid={onPaid} />} /></Routes>
            </MemoryRouter>,
        );

        expect(screen.getByText("결제를 확인하는 중입니다…")).toBeInTheDocument();
        expect(await screen.findByText("결제가 확인됐습니다.")).toBeInTheDocument();
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/payment/stripe/confirm",
            { trackingId: "track-1", providerPaymentId: "cs_test_1" });
        expect(onPaid).toHaveBeenCalledTimes(1);
    });

    it("🔴 onPaid 로 화면이 다시 떠도 확인 요청은 한 번뿐이다 — 쿼리를 onPaid 보다 먼저 지운다", async () => {
        postRequest.mockResolvedValue({ data: {} });
        renderAt("/order/7?stripe=success&session_id=cs_test_1");

        await waitFor(() => expect(screen.getByTestId("gen")).toHaveTextContent("1"));   // onPaid 가 불렸다
        expect(screen.getByTestId("search")).toHaveTextContent("");                      // 주소에서 세션 id 가 사라졌다
        await new Promise((r) => setTimeout(r, 20));
        expect(postRequest).toHaveBeenCalledTimes(1);
        expect(screen.getByTestId("gen")).toHaveTextContent("1");                        // 더 돌지 않는다
    });

    it("확인이 거절돼도 쿼리를 지우고, 웹훅이 반영할 수 있다고 말한다", async () => {
        postRequest.mockRejectedValue({ response: { data: { message: "세션을 찾을 수 없습니다." } } });
        renderAt("/order/7?stripe=success&session_id=cs_test_1");

        expect(await screen.findByRole("alert")).toHaveTextContent("세션을 찾을 수 없습니다.");
        expect(screen.getByTestId("search")).toHaveTextContent("");
        expect(screen.getByTestId("gen")).toHaveTextContent("0");                        // 실패에는 onPaid 없음
    });

    /** 확정·환불된 뒤에는 띠를 내린다 — 새로고침 없이 주문만 다시 읽으므로 로컬 상태가 남아 있다. */
    it("주문이 확정되거나 환불되면 아무것도 그리지 않는다", async () => {
        postRequest.mockResolvedValue({ data: {} });
        const { rerender, container } = render(
            <MemoryRouter initialEntries={["/order/7?stripe=success&session_id=cs_test_1"]}>
                <Routes><Route path="/order/:id" element={<StripeReturn order={{ trackingId: "t" }} onPaid={jest.fn()} />} /></Routes>
            </MemoryRouter>,
        );
        expect(await screen.findByText("결제가 확인됐습니다.")).toBeInTheDocument();

        rerender(
            <MemoryRouter initialEntries={["/order/7"]}>
                <Routes><Route path="/order/:id" element={<StripeReturn order={{ trackingId: "t", orderStatus: "REFUNDED" }} onPaid={jest.fn()} />} /></Routes>
            </MemoryRouter>,
        );
        expect(container).toBeEmptyDOMElement();
    });

    it("취소하고 돌아오면 요청 없이 안내만 보여 준다", () => {
        renderAt("/order/7?stripe=canceled");
        expect(screen.getByText(/결제를 취소했습니다/)).toBeInTheDocument();
        expect(postRequest).not.toHaveBeenCalled();
    });
});
