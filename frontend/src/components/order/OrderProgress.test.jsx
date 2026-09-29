import { render, screen } from "@testing-library/react";

import OrderProgress, { stepOf } from "components/order/OrderProgress";

jest.mock("i18n/I18nProvider", () => ({
    useTranslation: () => ({ t: (key) => key }),
}));

const states = () => screen.getAllByRole("listitem").map((li) => li.getAttribute("data-state"));

/**
 * 결제 확인 직후 주문은 아직 NOT_PROCESSED 다(사가가 PAID 로 바꾼다). 예전 화면은 그 순간
 * "결제가 완료되었습니다" 라고 말했고, 바로 옆의 결제 상태는 X 였다 — 사용자에게는 모순.
 * 이 컴포넌트는 지금 어디까지 왔는지를 그리고, 기다리는 동안은 "확정하는 중" 이라고 말한다.
 */
describe("OrderProgress — 주문 진행 단계", () => {
    it("상태 → 단계 매핑", () => {
        expect(stepOf("NOT_PROCESSED")).toBe(0);
        expect(stepOf("UNPAID")).toBe(0);
        expect(stepOf("PAID")).toBe(1);
        expect(stepOf("PROCESSING")).toBe(2);
        expect(stepOf("DISPATCHED")).toBe(3);
        expect(stepOf("DELIVERED")).toBe(4);
        expect(stepOf("COMPLETED")).toBe(4);
        expect(stepOf(undefined)).toBe(0);
    });

    it("결제 전이면 첫 단계가 현재이고 결제를 기다린다고 말한다", () => {
        render(<OrderProgress order={{ orderStatus: "NOT_PROCESSED" }} />);
        expect(states()).toEqual(["current", "todo", "todo", "todo", "todo"]);
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.awaiting_payment");
    });

    /** 🔴 핵심 — 확인은 됐고 확정은 아직. "완료" 가 아니라 "확정하는 중". */
    it("🔴 정산을 기다리는 동안은 결제는 끝났고 확정이 진행 중이다", () => {
        render(<OrderProgress order={{ orderStatus: "NOT_PROCESSED" }} settling />);
        expect(states()).toEqual(["done", "current", "todo", "todo", "todo"]);
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.settling");
    });

    it("시한을 넘기면 늦어진다고 정직하게 말한다 — 경고로", () => {
        render(<OrderProgress order={{ orderStatus: "NOT_PROCESSED" }} timedOut />);
        expect(states()).toEqual(["done", "current", "todo", "todo", "todo"]);
        expect(screen.getByRole("alert")).toHaveTextContent("order.progress.settle_timeout");
    });

    it("확정되면 상품 준비 안내, 배송 중이면 네 번째 단계", () => {
        const { rerender } = render(<OrderProgress order={{ orderStatus: "PAID" }} />);
        expect(states()).toEqual(["done", "current", "todo", "todo", "todo"]);
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.preparing_hint");

        rerender(<OrderProgress order={{ orderStatus: "DISPATCHED" }} />);
        expect(states()).toEqual(["done", "done", "done", "current", "todo"]);
        expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("무통장입금 대기는 입금을 기다린다고 말한다", () => {
        render(<OrderProgress order={{ orderStatus: "UNPAID" }} />);
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.awaiting_deposit");
    });

    it("취소·환불은 단계 대신 띠 하나", () => {
        const { rerender } = render(<OrderProgress order={{ orderStatus: "CANCELED" }} />);
        expect(screen.queryByRole("list")).not.toBeInTheDocument();
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.canceled");
        rerender(<OrderProgress order={{ orderStatus: "REFUNDED" }} />);
        expect(screen.getByRole("status")).toHaveTextContent("order.progress.refunded");
    });
});
