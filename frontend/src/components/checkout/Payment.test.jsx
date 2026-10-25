import { render as rtlRender, screen } from "@testing-library/react";

import PaymentCheckout from "components/checkout/Payment";
import { withI18n } from "i18n/testing";

const render = (ui) => rtlRender(withI18n(ui));

/**
 * ⏸ 2026-10-25: 포트원(KG이니시스 일본결제)은 결제창이 이니시스 테스트 서버에서 연결 시간 초과라 보류 — 체크아웃에서 고를 수 없다.
 * 다시 열 때는 HIDDEN_METHODS 에서 빼고 이 테스트의 기대도 바꾼다.
 *
 * <p>[JA] PortOne は保留 — チェックアウトで選べない。再開時は HIDDEN_METHODS から外し、このテストの期待も変える。
 */
describe("체크아웃 — 결제수단 고르기", () => {
    it("🔴 포트원은 보이지 않고 나머지(PayPal · Stripe · 무통장입금)는 그대로", () => {
        render(<PaymentCheckout paymentMethod="" setPaymentMethod={jest.fn()} />);

        const ids = screen.getAllByRole("radio").map((r) => r.id);
        expect(ids).not.toContain("portone");
        expect(ids).toEqual(expect.arrayContaining(["paypal", "stripe", "cash"]));
    });
});
