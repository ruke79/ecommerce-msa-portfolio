import { fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";

import ConfirmPurchaseButton from "components/order/ConfirmPurchaseButton";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({ confirmPurchase: jest.fn() }));
jest.mock("react-hot-toast", () => ({
    __esModule: true,
    default: { success: jest.fn(), error: jest.fn() },
}));

const { confirmPurchase } = require("util/api");
const toast = require("react-hot-toast").default;

const order = (orderStatus, over = {}) => ({ id: "123456789012345678", orderStatus, ...over });

beforeEach(() => jest.clearAllMocks());

/**
 * 🐛 COMPLETED 는 서버에 선언만 있고 그 값을 넣는 코드가 없었다. 배달 완료가 끝이었고,
 * 프로필의 "구매 확정" 필터는 영원히 비어 있었다.
 */
describe("ConfirmPurchaseButton — 상태별 모습", () => {
    it("배달 완료면 버튼과 자동 확정 안내가 있다", () => {
        render(<ConfirmPurchaseButton order={order("DELIVERED")} />);
        expect(screen.getByRole("button", { name: "구매 확정" })).toBeInTheDocument();
        expect(screen.getByText(/자동으로 확정됩니다/)).toBeInTheDocument();
    });

    /** 서버가 거절하는 상태에는 버튼을 그리지 않는다 — 눌러 보고 거절당하게 두지 않는다. */
    it("배달 완료가 아니면 아무것도 그리지 않는다", () => {
        ["PAID", "PROCESSING", "DISPATCHED", "REFUNDED", "CANCELED", "NOT_PROCESSED", "UNPAID"].forEach((s) => {
            const { container, unmount } = render(<ConfirmPurchaseButton order={order(s)} />);
            expect(container).toBeEmptyDOMElement();
            unmount();
        });
    });

    it("확정된 주문은 확정 시각을 보여 준다", () => {
        render(<ConfirmPurchaseButton order={order("COMPLETED", { completedAt: "2026-09-20T03:00:00" })} />);
        expect(screen.getByTestId("purchase-completed")).toHaveTextContent("구매가 확정된 주문입니다.");
        expect(screen.getByText(/확정 시각/)).toBeInTheDocument();
        expect(screen.queryByRole("button")).not.toBeInTheDocument();
        expect(screen.queryByText(/자동으로 확정되었습니다/)).not.toBeInTheDocument();
    });

    /** 손님이 누른 적이 없는데 상태가 바뀌어 있으면 이유를 알아야 한다. */
    it("자동 확정이면 그렇다고 말한다", () => {
        render(<ConfirmPurchaseButton order={order("COMPLETED", { completedAt: "2026-09-20T03:00:00", autoCompleted: true })} />);
        expect(screen.getByText(/자동으로 확정되었습니다/)).toBeInTheDocument();
    });
});

describe("ConfirmPurchaseButton — 확정", () => {
    it("🔴 한 번 더 묻고, 확정하면 주문 번호만 보내고 다시 읽게 한다", async () => {
        confirmPurchase.mockResolvedValue({ message: "구매가 확정되었습니다.", orderStatus: "COMPLETED" });
        const onConfirmed = jest.fn();
        render(<ConfirmPurchaseButton order={order("DELIVERED")} onConfirmed={onConfirmed} />);

        fireEvent.click(screen.getByRole("button", { name: "구매 확정" }));
        expect(confirmPurchase).not.toHaveBeenCalled();   // 아직 — 되돌릴 수 없는 일이라 한 번 더 묻는다
        expect(screen.getByText(/되돌릴 수 없습니다/)).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "확정" }));

        await waitFor(() => expect(onConfirmed).toHaveBeenCalled());
        // 18자리 주문 번호가 문자열 그대로 간다 — 숫자로 바꾸면 값이 바뀐다.
        expect(confirmPurchase).toHaveBeenCalledWith("123456789012345678");
        expect(toast.success).toHaveBeenCalledWith("구매가 확정되었습니다.");
    });

    it("돌아가기를 누르면 아무것도 보내지 않는다", () => {
        render(<ConfirmPurchaseButton order={order("DELIVERED")} />);
        fireEvent.click(screen.getByRole("button", { name: "구매 확정" }));
        fireEvent.click(screen.getByRole("button", { name: "돌아가기" }));
        expect(confirmPurchase).not.toHaveBeenCalled();
        expect(screen.getByRole("button", { name: "구매 확정" })).toBeInTheDocument();
    });

    it("서버가 거절하면 그 이유를 보여 주고 다시 읽지 않는다", async () => {
        confirmPurchase.mockRejectedValue({ response: { data: { message: "배송이 완료된 주문만 구매 확정할 수 있습니다: status=DISPATCHED" } } });
        const onConfirmed = jest.fn();
        render(<ConfirmPurchaseButton order={order("DELIVERED")} onConfirmed={onConfirmed} />);

        fireEvent.click(screen.getByRole("button", { name: "구매 확정" }));
        fireEvent.click(screen.getByRole("button", { name: "확정" }));

        await waitFor(() => expect(toast.error).toHaveBeenCalledWith(expect.stringContaining("배송이 완료된 주문만")));
        expect(onConfirmed).not.toHaveBeenCalled();
    });
});

describe("ConfirmPurchaseButton (일본어)", () => {
    it("안내와 버튼이 일본어다 — 한국어가 남지 않는다", () => {
        rtlRender(withI18n(<ConfirmPurchaseButton order={order("DELIVERED")} />, "ja"));
        expect(screen.getByRole("button", { name: "購入を確定する" })).toBeInTheDocument();
        expect(screen.getByText(/自動的に確定されます/)).toBeInTheDocument();
        expect(document.body.textContent).not.toMatch(/[가-힣]/);
    });
});
