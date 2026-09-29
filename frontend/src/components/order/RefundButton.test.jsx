import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";

import RefundButton from "components/order/RefundButton";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({ postRequest: jest.fn() }));
jest.mock("react-hot-toast", () => ({
    __esModule: true,
    default: { success: jest.fn(), error: jest.fn() },
}));

const { postRequest } = require("util/api");
const toast = require("react-hot-toast").default;

const order = (orderStatus, over = {}) => ({
    id: 1,
    trackingId: "track-1",
    total: 59.98,
    orderStatus,
    ...over,
});

beforeEach(() => jest.clearAllMocks());

describe("RefundButton — 상태별 모습", () => {
    it("발송 전이면 버튼이 있다", () => {
        render(<RefundButton order={order("PAID")} />);
        expect(screen.getByRole("button", { name: /환불 요청/ })).toBeInTheDocument();
    });

    /** 서버가 거절하는 상태에는 버튼을 그리지 않는다 — 눌러 보고 거절당하게 두지 않는다. */
    it("발송 뒤에는 버튼 대신 고객센터 안내", () => {
        render(<RefundButton order={order("DISPATCHED")} />);
        expect(screen.queryByRole("button")).not.toBeInTheDocument();
        expect(screen.getByText(/고객센터/)).toBeInTheDocument();
    });

    /** 도착한 물건은 돌려보내야 환불된다 — "배송이 시작됐다" 는 오는 중인 물건에 하는 말이다. */
    it("배송 완료 뒤에는 반품 절차를 안내한다", () => {
        render(<RefundButton order={order("DELIVERED")} />);
        expect(screen.queryByRole("button")).not.toBeInTheDocument();
        expect(screen.getByText(/반품 절차/)).toBeInTheDocument();
        expect(screen.queryByText(/배송이 시작된/)).not.toBeInTheDocument();
    });

    it("결제 전 주문에는 아무것도 없다 — 환불이 아니라 취소의 영역", () => {
        const { container } = render(<RefundButton order={order("NOT_PROCESSED")} />);
        expect(container).toBeEmptyDOMElement();
    });

    /** 돌려주는 것은 제공자에게 청구한 금액이다 — 상점 통화의 total 이 아니다. */
    it("확인 문구는 청구 통화·금액이다 — JPY 로 결제했으면 ¥, 센트도 그대로", () => {
        const { unmount } = render(
            <RefundButton order={order("PAID", { currency: "JPY", chargedAmount: 8850 })} />);
        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        expect(screen.getByText(/8,850/)).toBeInTheDocument();
        unmount();

        render(<RefundButton order={order("PAID")} />);   // 옛 주문 — 상점 통화로 total
        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        // 한국어 화면(ko-KR)의 달러 표기는 "US$" 다 — 통화 표기는 앱 언어를 따른다(useMoney).
        expect(screen.getByText("US$59.98")).toBeInTheDocument();
    });

    it("환불된 주문은 참조와 함께 완료로 보인다", () => {
        render(<RefundButton order={order("REFUNDED", { refundId: "re_123" })} />);
        expect(screen.getByText(/환불이 완료/)).toBeInTheDocument();
        expect(screen.getByText(/re_123/)).toBeInTheDocument();
        expect(screen.queryByText(/반품 접수 중/)).not.toBeInTheDocument();
    });

    it("발송 뒤 환불된 주문은 반품 접수 중 — 입고되면 완료", () => {
        const { unmount } = render(<RefundButton order={order("REFUNDED", { awaitingReturn: true })} />);
        expect(screen.getByText(/반품 접수 중/)).toBeInTheDocument();
        unmount();

        render(<RefundButton order={order("REFUNDED", { awaitingReturn: false, returnReceivedAt: "2026-09-19T10:00:00" })} />);
        expect(screen.getByText(/반품 입고 완료/)).toBeInTheDocument();
    });
});

describe("RefundButton — 요청", () => {
    /** 🔒 보내는 것은 어느 주문·왜 뿐이다. 금액·결제 참조는 본문에 없어야 한다. */
    it("🔒 확정하면 trackingId 와 사유만 보낸다", async () => {
        postRequest.mockResolvedValue({ data: { message: "환불이 처리되었습니다." } });
        const onRefunded = jest.fn();
        render(<RefundButton order={order("PAID")} onRefunded={onRefunded} />);

        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        fireEvent.change(screen.getByLabelText("환불 사유"), { target: { value: "단순 변심" } });
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(onRefunded).toHaveBeenCalled());
        expect(postRequest).toHaveBeenCalledWith("/order-service/api/order/refund", {
            trackingId: "track-1",
            reason: "단순 변심",
        });
        const body = postRequest.mock.calls[0][1];
        expect(body).not.toHaveProperty("total");
        expect(body).not.toHaveProperty("amount");
        expect(body).not.toHaveProperty("providerPaymentId");
        expect(toast.success).toHaveBeenCalled();
    });

    it("서버가 거절하면 그 이유를 보여 주고 완료로 바꾸지 않는다", async () => {
        postRequest.mockRejectedValue({
            response: { data: { message: "배송이 시작된 주문은 여기서 환불할 수 없습니다." } },
        });
        const onRefunded = jest.fn();
        render(<RefundButton order={order("PAID")} onRefunded={onRefunded} />);

        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(toast.error).toHaveBeenCalledWith(
            "배송이 시작된 주문은 여기서 환불할 수 없습니다."));
        expect(onRefunded).not.toHaveBeenCalled();
    });

    /** 🔒 부분 환불 — 품목·수량만. 금액은 서버가 정한다. */
    it("🔒 품목 수량을 줄이면 items 를 보내고 금액은 보내지 않는다", async () => {
        postRequest.mockResolvedValue({ data: { message: "선택한 품목이 환불되었습니다." } });
        render(<RefundButton order={order("PAID", {
            products: [
                { id: "101", name: "티셔츠", size: "M", qty: 2, price: 20, refundedQty: 0 },
                { id: "102", name: "바지", size: "L", qty: 1, price: 10, refundedQty: 1 },
            ],
            refundedAmount: 10,
        })} />);

        expect(screen.getByText(/지금까지 환불/)).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: /나머지 환불 요청/ }));
        expect(screen.getByLabelText("환불 수량 바지")).toBeDisabled();
        fireEvent.change(screen.getByLabelText("환불 수량 티셔츠"), { target: { value: "1" } });
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        await waitFor(() => expect(postRequest).toHaveBeenCalled());
        const body = postRequest.mock.calls[0][1];
        expect(body.items).toEqual([{ orderProductId: "101", qty: 1 }]);
        expect(body).not.toHaveProperty("amount");
        expect(body).not.toHaveProperty("total");
    });

    it("전부 0 으로 두면 보내지 않는다", () => {
        render(<RefundButton order={order("PAID", {
            products: [{ id: "101", name: "티셔츠", qty: 1, refundedQty: 0 }],
        })} />);
        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        fireEvent.change(screen.getByLabelText("환불 수량 티셔츠"), { target: { value: "0" } });
        fireEvent.click(screen.getByRole("button", { name: "환불 확정" }));

        expect(postRequest).not.toHaveBeenCalled();
        expect(toast.error).toHaveBeenCalled();
    });

    it("돌아가기를 누르면 아무것도 보내지 않는다", () => {
        render(<RefundButton order={order("PAID")} />);
        fireEvent.click(screen.getByRole("button", { name: /환불 요청/ }));
        fireEvent.click(screen.getByRole("button", { name: "돌아가기" }));

        expect(postRequest).not.toHaveBeenCalled();
        expect(screen.getByRole("button", { name: /환불 요청/ })).toBeInTheDocument();
    });
});
