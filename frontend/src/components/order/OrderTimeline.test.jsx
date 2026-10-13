import { render as rtlRender, screen, waitFor } from "@testing-library/react";

import OrderTimeline, { sourceLabel } from "components/order/OrderTimeline";
import { withI18n } from "i18n/testing";

const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({
    getOrderTimeline: jest.fn(),
}));

const { getOrderTimeline } = require("util/api");

const view = (overrides = {}) => ({
    orderId: 42,
    orderStatus: "PAID",
    stale: false,
    timeline: [
        { previousStatus: null, status: "NOT_PROCESSED", source: "created", occurredAt: "2026-10-07T01:00:00Z" },
        { previousStatus: "NOT_PROCESSED", status: "PAID", source: "payment-saga", occurredAt: "2026-10-07T01:00:12Z" },
    ],
    ...overrides,
});

beforeEach(() => jest.clearAllMocks());

/**
 * 읽기 모델(saga-projection)에서 온 진행 기록(docs/76). 주문 상세와 별개의 출처라 없을 수 있다 — 그때는 아무것도 그리지
 * 않는다. 주문 상세는 이 컴포넌트 없이도 완전하다.
 */
describe("OrderTimeline — 주문 진행 기록", () => {
    it("기록을 발생 순서대로, 상태 이름과 출처를 사람이 읽는 말로 그린다", async () => {
        getOrderTimeline.mockResolvedValue(view());
        render(<OrderTimeline orderId={42} orderStatus="PAID" />);

        const items = await screen.findAllByRole("listitem");
        expect(items.map((li) => li.getAttribute("data-status"))).toEqual(["NOT_PROCESSED", "PAID"]);
        expect(items[0]).toHaveTextContent("결제 대기");
        expect(items[0]).toHaveTextContent("주문 생성");
        expect(items[1]).toHaveTextContent("결제 완료");
        expect(items[1]).toHaveTextContent("결제 확인");
        expect(screen.queryByRole("note")).not.toBeInTheDocument();
        expect(getOrderTimeline).toHaveBeenCalledWith(42);
    });

    /** 🔴 404(아직 이벤트 없음 · 남의 주문) · 503(프로젝션 준비 중)은 오류가 아니다 — 조용히 비운다. */
    it("🔴 읽기 모델이 답하지 않으면(404 · 503) 아무것도 그리지 않는다", async () => {
        getOrderTimeline.mockRejectedValue({ response: { status: 503 } });
        render(<OrderTimeline orderId={42} orderStatus="PAID" />);

        await waitFor(() => expect(getOrderTimeline).toHaveBeenCalled());
        expect(screen.queryByTestId("order-timeline")).not.toBeInTheDocument();
    });

    it("기록이 비어 있어도 그리지 않는다", async () => {
        getOrderTimeline.mockResolvedValue(view({ timeline: [] }));
        render(<OrderTimeline orderId={42} orderStatus="PAID" />);

        await waitFor(() => expect(getOrderTimeline).toHaveBeenCalled());
        expect(screen.queryByTestId("order-timeline")).not.toBeInTheDocument();
    });

    it("대기 복제본에서 답했으면(stale) 늦을 수 있다고 말한다", async () => {
        getOrderTimeline.mockResolvedValue(view({ stale: true }));
        render(<OrderTimeline orderId={42} orderStatus="PAID" />);

        expect(await screen.findByRole("note")).toHaveTextContent("아직 반영되지 않았을 수 있습니다");
    });

    it("주문 상태가 바뀌면 다시 읽는다 — 기록도 늘었을 것이다", async () => {
        getOrderTimeline.mockResolvedValue(view());
        const { rerender } = render(<OrderTimeline orderId={42} orderStatus="PAID" />);
        await waitFor(() => expect(getOrderTimeline).toHaveBeenCalledTimes(1));

        rerender(<OrderTimeline orderId={42} orderStatus="PROCESSING" />);
        await waitFor(() => expect(getOrderTimeline).toHaveBeenCalledTimes(2));
    });

    it("모르는 출처 코드는 그대로 보여 준다 — 빈칸보다 낫다", () => {
        const t = (key) => (key === "order.timeline.source.delivery" ? "배송" : key);
        expect(sourceLabel(t, "delivery")).toBe("배송");
        expect(sourceLabel(t, "something-new")).toBe("something-new");
        expect(sourceLabel(t, null)).toBe("");
    });
});
