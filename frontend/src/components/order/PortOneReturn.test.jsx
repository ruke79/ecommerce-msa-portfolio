import { render as rtlRender, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import PortOneReturn from "components/order/PortOneReturn";
import { withI18n } from "i18n/testing";

const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({ postRequest: jest.fn() }));
const { postRequest } = require("util/api");

const order = { trackingId: "track-1", orderStatus: "NOT_PROCESSED" };

function Page({ onConfirmed }) {
    const location = useLocation();
    return (
        <>
            <span data-testid="search">{location.search}</span>
            <PortOneReturn order={order} onConfirmed={onConfirmed} />
        </>
    );
}

const renderAt = (url, onConfirmed = jest.fn()) => {
    render(
        <MemoryRouter initialEntries={[url]}>
            <Routes><Route path="/order/:id" element={<Page onConfirmed={onConfirmed} />} /></Routes>
        </MemoryRouter>,
    );
    return onConfirmed;
};

beforeEach(() => jest.clearAllMocks());

/**
 * 🐛 2026-10-24 운영(KG이니시스 V2): 결제창이 리디렉션으로 열리는 환경에서는 {@code requestPayment} 의 반환값이 없고 결과가
 * 이 주문 화면의 쿼리로 돌아온다. 예전에는 돌아올 주소도, 돌아온 뒤의 확인도 없었다.
 *
 * <p>[JA] リダイレクトで決済画面が開く環境では戻り値がなく、結果が注文画面のクエリで戻る。以前は戻り先も戻った後の確認もなかった。
 */
describe("PortOneReturn — 리디렉션 복귀", () => {
    it("🔴 성공 복귀 — 결제 id 하나로 확인하고, 쿼리를 지우고, 결제됨을 알린다", async () => {
        postRequest.mockResolvedValue({ data: { orderStatus: "PAID" } });
        const onConfirmed = renderAt("/order/7?paymentId=payment-abc&transactionType=PAYMENT&txId=tx-1");

        expect(await screen.findByText("결제가 확인됐습니다.")).toBeInTheDocument();
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/payment/portone/confirm",
            { trackingId: "track-1", providerPaymentId: "payment-abc" });
        expect(onConfirmed).toHaveBeenCalledWith({ orderStatus: "PAID" });
        await waitFor(() => expect(screen.getByTestId("search")).toHaveTextContent(/^$/));
    });

    it("가상계좌 발급 복귀 — 입금 대기(UNPAID)를 알린다", async () => {
        postRequest.mockResolvedValue({ data: { orderStatus: "UNPAID" } });
        const onConfirmed = renderAt("/order/7?paymentId=payment-vb");

        await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith({ orderStatus: "UNPAID" }));
        expect(screen.queryByText("결제가 확인됐습니다.")).not.toBeInTheDocument();
    });

    it("🔴 실패 복귀(사용자 취소) — 확인하지 않고 이유를 보여 주고 쿼리를 지운다", async () => {
        const onConfirmed = renderAt("/order/7?paymentId=payment-abc&code=FAILURE_TYPE_PG&message=" +
            encodeURIComponent("[결제포기] 사용자가 결제를 취소하셨습니다"));

        expect(await screen.findByRole("alert")).toBeInTheDocument();
        expect(postRequest).not.toHaveBeenCalled();
        expect(onConfirmed).not.toHaveBeenCalled();
        await waitFor(() => expect(screen.getByTestId("search")).toHaveTextContent(/^$/));
    });

    it("확인이 실패해도 결제가 사라진 것은 아니다 — '확인 지연' 안내", async () => {
        postRequest.mockRejectedValue({ response: { data: { message: "잠시 뒤 다시" } } });
        renderAt("/order/7?paymentId=payment-abc");

        expect(await screen.findByRole("alert")).toHaveTextContent("잠시 뒤 다시");
    });

    it("우리 결제 id 가 아닌 쿼리는 건드리지 않는다", () => {
        renderAt("/order/7?paymentId=other-1");
        expect(postRequest).not.toHaveBeenCalled();
        expect(screen.getByTestId("search")).toHaveTextContent("?paymentId=other-1");
    });
});
