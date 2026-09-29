import { act, render as rtlRender, screen } from "@testing-library/react";

import StockHoldNotice from "components/order/StockHoldNotice";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

/** 서버가 보내는 모양 그대로 — 시간대 없는 UTC 시각. */
const naiveUtc = (date) => date.toISOString().replace("Z", "");

const order = (overrides) => ({ orderStatus: "NOT_PROCESSED", ...overrides });

afterEach(() => jest.useRealTimers());

describe("결제 전 재고 예약 안내 (docs/59)", () => {
    it("예약 시각 전이면 그 시각까지 결제해 달라고 말한다", () => {
        const until = new Date(Date.now() + 30 * 60 * 1000);
        render(<StockHoldNotice order={order({ stockHoldUntil: naiveUtc(until) })} />);

        const notice = screen.getByTestId("stock-hold-notice");
        expect(notice).toHaveTextContent("이 시간 안에 결제해 주세요");
        // 시간대 없는 값을 UTC 로 읽었는가 — 현지 시각으로 읽으면 시간대만큼 어긋난다.
        expect(notice).toHaveTextContent(until.toLocaleString());
        expect(screen.queryByTestId("stock-hold-expired")).toBeNull();
    });

    it("예약 시각이 지났으면 재고가 보장되지 않는다고 말한다", () => {
        const until = new Date(Date.now() - 60 * 1000);
        render(<StockHoldNotice order={order({ stockHoldUntil: naiveUtc(until) })} />);

        expect(screen.getByTestId("stock-hold-expired")).toHaveTextContent("재고가 보장되지 않습니다");
        expect(screen.queryByTestId("stock-hold-notice")).toBeNull();
    });

    it("화면을 켜 둔 채 기한이 지나면 문구가 바뀐다", () => {
        jest.useFakeTimers();
        const until = new Date(Date.now() + 5 * 1000);
        render(<StockHoldNotice order={order({ stockHoldUntil: naiveUtc(until) })} />);
        expect(screen.getByTestId("stock-hold-notice")).toBeInTheDocument();

        act(() => { jest.advanceTimersByTime(7 * 1000); });

        expect(screen.getByTestId("stock-hold-expired")).toBeInTheDocument();
    });

    it("입금 대기(UNPAID)에서도 보인다 — 무통장·가상계좌는 입금 기한까지 연장된 예약이다", () => {
        const until = new Date(Date.now() + 49 * 3600 * 1000);
        render(<StockHoldNotice order={order({ orderStatus: "UNPAID", stockHoldUntil: naiveUtc(until) })} />);

        expect(screen.getByTestId("stock-hold-notice")).toBeInTheDocument();
    });

    it("예약 시각이 없으면 아무것도 그리지 않는다 — 잡아 두었다고 말할 근거가 없다", () => {
        const { container } = render(<StockHoldNotice order={order({ stockHoldUntil: null })} />);
        expect(container).toBeEmptyDOMElement();
    });

    it.each(["PAID", "CANCELED", "COMPLETED"])("%s 주문에는 그리지 않는다", (status) => {
        const until = new Date(Date.now() + 30 * 60 * 1000);
        const { container } = render(
            <StockHoldNotice order={order({ orderStatus: status, stockHoldUntil: naiveUtc(until) })} />);
        expect(container).toBeEmptyDOMElement();
    });
});
