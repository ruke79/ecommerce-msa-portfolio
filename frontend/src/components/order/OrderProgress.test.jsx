import { render, screen } from "@testing-library/react";

import OrderProgress, { stepOf } from "components/order/OrderProgress";
import ja from "i18n/messages/ja";
import ko from "i18n/messages/ko";

jest.mock("i18n/I18nProvider", () => ({
    useTranslation: () => ({ t: (key) => key }),
}));

const states = () => screen.getAllByRole("listitem").map((li) => li.getAttribute("data-state"));

/**
 * 결제 확인 직후 주문은 아직 NOT_PROCESSED 다(사가가 PAID 로 바꾼다). 예전 화면은 그 순간
 * "결제가 완료되었습니다" 라고 말했고, 바로 옆의 결제 상태는 X 였다 — 사용자에게는 모순.
 * 이 컴포넌트는 지금 어디까지 왔는지를 그리고, 기다리는 동안은 "확정하는 중" 이라고 말한다.
 *
 * <p>[JA] 決済確認の直後、注文はまだ NOT_PROCESSED である(サガが PAID に変える)。以前の画面はその瞬間に
 * 「決済が完了しました」と言い、すぐ横の決済状態は X だった — ユーザーには矛盾。
 * このコンポーネントは今どこまで来たかを描き、待つ間は「確定中」と言う。
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
    // [JA] 🔴 核心 — 確認は済み確定はまだ。「完了」ではなく「確定中」。
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
    /**
     * 🔴 네 번째 단계(DISPATCHED)의 이름은 주문 상태 글자와 같다 — "配送中" 이던 때는 아래 배송 추적이 같은 주문을 "発送しました ·
     * 비행기(配送中) 회색" 으로 그려 둘이 엇갈려 보였다(2026-11-20 실습). 택배사가 이동 중을 알리기 전이라 "発送済み" 가 맞다.
     * [JA] 4 番目の段階の名前は注文状態の文字と同じ — 「配送中」だった頃は下の配送追跡と食い違って見えた。
     */
    it("🔴 발송 단계의 이름은 주문 상태(DISPATCHED)의 글자와 같다 — 配送中 이 아니다", () => {
        expect(ja["order.progress.shipping"]).toBe(ja["status.order.DISPATCHED"]);
        expect(ja["order.progress.shipping"]).toBe("発送済み");
        expect(ko["order.progress.shipping"]).toBe(ko["status.order.DISPATCHED"]);
    });
});
