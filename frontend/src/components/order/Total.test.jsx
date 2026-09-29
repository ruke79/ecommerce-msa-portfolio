import { render, screen } from "@testing-library/react";

import Total from "components/order/Total";

jest.mock("i18n/I18nProvider", () => ({
    useTranslation: () => ({ t: (key) => key }),
    // 통화 표기는 앱 언어를 따른다(useMoney). 언어가 없으면 기본 로캘 — "$59.98".
    useCurrentLang: () => null,
}));

/**
 * 🐛 예전에는 `{order.total}$` 였다 — 서버가 int 를 주던 시절 "59$" 로 찍혔고, 통화 기호는
 * 상점 통화와 무관하게 달러였다. V7 부터 서버가 59.98 을 주므로 센트가 보여야 하고,
 * 기호와 자릿수는 Intl 이 상점 통화(빌드 인자, 테스트에서는 USD)로 정한다.
 */
describe("Total — 주문 상세의 금액 세 줄", () => {
    it("센트가 보인다 — 59.98 은 $59.98 이지 59$ 가 아니다", () => {
        render(<Total order={{ total: 59.98, totalBeforeDiscount: 59.98 }} />);
        expect(screen.getAllByText("$59.98")).toHaveLength(2);
        expect(screen.queryByText(/59\$/)).not.toBeInTheDocument();
    });

    it("서버가 문자열로 준 금액도 숫자로 다룬다", () => {
        render(<Total order={{ total: "53.98", totalBeforeDiscount: "59.98", couponApplied: "TEN" }} />);
        expect(screen.getByText("$59.98")).toBeInTheDocument();
        expect(screen.getByText("$53.98")).toBeInTheDocument();
        expect(screen.getByText(/- \$6\.00/)).toBeInTheDocument();
    });

    it("쿠폰이 없으면 할인 줄이 없다", () => {
        render(<Total order={{ total: 100, totalBeforeDiscount: 100 }} />);
        expect(screen.queryByText("order.total.coupon")).not.toBeInTheDocument();
        expect(screen.getByText("order.total.free")).toBeInTheDocument();
    });
});
