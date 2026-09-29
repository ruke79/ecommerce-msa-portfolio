import { render, screen } from "@testing-library/react";

import ShippingInfo from "components/product/ShippingInfo";
import { withI18n } from "i18n/testing";

const product = { price: 100, shipping: 0, quantity: 5 };

const renderInfo = (over = {}, lang = "ko") =>
    render(withI18n(<ShippingInfo product={{ ...product, ...over }} />, lang));

/**
 * 🐛 <b>이 화면에 사실이 아닌 정보가 박혀 있었다.</b> Amazon 클론에서 딸려 온 것들이다:
 * 고정된 배송일("Thursday, March 23")과 가짜 카운트다운("23 hrs 53 mins"),
 * "Deliver to Germany", 존재하지 않는 판매자 "Sold by ATUAT".
 *
 * <p>번역하지 않고 지웠다 — 거짓 정보를 일본어로도 보여 줄 이유가 없다. 사용자는 그 날짜를
 * 믿고 주문한다.
 */
describe("배송·재고 안내", () => {
    describe("지어낸 정보를 보여주지 않는다", () => {
        /** 🔴 되살아나면 사용자가 없는 배송일을 믿게 된다. */
        it("🔴 고정된 배송일과 카운트다운이 없다", () => {
            renderInfo();

            expect(screen.queryByText(/March 23/)).not.toBeInTheDocument();
            expect(screen.queryByText(/hrs.*mins/)).not.toBeInTheDocument();
        });

        it("배송지를 독일이라고 말하지 않는다", () => {
            renderInfo();
            expect(screen.queryByText(/Germany/)).not.toBeInTheDocument();
        });

        it("존재하지 않는 판매자를 적지 않는다", () => {
            renderInfo();
            expect(screen.queryByText(/ATUAT/)).not.toBeInTheDocument();
        });
    });

    describe("실제 값은 보여준다", () => {
        it("배송비가 없으면 무료 배송이라고 한다", () => {
            renderInfo({ shipping: 0 });
            expect(screen.getByText("무료 배송")).toBeInTheDocument();
        });

        it("배송비가 있으면 금액을 보여준다", () => {
            renderInfo({ shipping: 3000 });
            expect(screen.getByText("배송비 US$3,000.00")).toBeInTheDocument();
        });

        /** 🐛 예전에는 `quantity > 1` 이라 마지막 1개가 "품절" 로 보였다. */
        it("마지막 1개도 재고 있음이다", () => {
            renderInfo({ quantity: 1 });
            expect(screen.getByText("재고 있음")).toBeInTheDocument();
        });

        it("재고 유무를 보여준다", () => {
            renderInfo({ quantity: 5 });
            expect(screen.getByText("재고 있음")).toBeInTheDocument();

            renderInfo({ quantity: 0 });
            expect(screen.getAllByText("품절").length).toBeGreaterThan(0);
        });
    });

    /**
     * 🔴 키만 바꿔 놓고 컴포넌트가 여전히 하드코딩된 영어를 그리고 있어도 한국어 테스트는
     * 통과한다 — 일본어로 그려 봐야 드러난다.
     */
    describe("일본어", () => {
        it("🔴 일본어로 렌더된다", () => {
            renderInfo({ shipping: 0 }, "ja");

            expect(screen.getByText("送料無料")).toBeInTheDocument();
            expect(screen.getByText("在庫あり")).toBeInTheDocument();
            expect(screen.getByText("安全なお支払い")).toBeInTheDocument();
        });

        it("영어 원문이 남아 있지 않다", () => {
            renderInfo({ shipping: 0 }, "ja");

            expect(screen.queryByText("Free Shipping")).not.toBeInTheDocument();
            expect(screen.queryByText("In Stock")).not.toBeInTheDocument();
            expect(screen.queryByText("Secure transaction")).not.toBeInTheDocument();
        });

        it("배송비 금액이 치환된다 — {{amount}} 가 남으면 안 된다", () => {
            renderInfo({ shipping: 500 }, "ja");

            const fee = screen.getByText("送料 $500.00");
            expect(fee).toBeInTheDocument();
            expect(fee.textContent).not.toContain("{{amount}}");
        });
    });
});
