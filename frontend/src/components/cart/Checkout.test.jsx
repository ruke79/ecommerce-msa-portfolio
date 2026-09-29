import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import CartCheckout from "components/cart/Checkout";
import Empty from "components/cart/Empty";
import { withI18n } from "i18n/testing";

jest.mock("store/AuthContext", () => ({
    useAuthContext: () => ({ token: null }),
}));

const renderSummary = (over = {}, lang = "ko") =>
    render(
        withI18n(
            <CartCheckout
                subtotal={100000}
                shippingFee={0}
                total={100000}
                selected={[{ id: "1" }]}
                saveCartToDbHandler={() => {}}
                {...over}
            />,
            lang
        )
    );

/**
 * 장바구니의 금액 요약과 빈 장바구니 안내.
 *
 * <p>이 컴포넌트들에는 테스트가 <b>하나도 없었다.</b> 문구를 사전으로 옮기면서 여기서 처음
 * 렌더해 본다 — 번역 누락뿐 아니라 "빈 장바구니에서 버튼이 뜨는지" 같은 기본 동작도 함께
 * 고정된다.
 */
describe("장바구니 금액 요약", () => {
    it("한국어로 렌더된다", () => {
        renderSummary();

        expect(screen.getByText("주문 요약")).toBeInTheDocument();
        expect(screen.getByText("상품 금액")).toBeInTheDocument();
        expect(screen.getByText("합계")).toBeInTheDocument();
    });

    /** 🔴 사전만 채우고 컴포넌트가 영어를 그리고 있으면 여기서 드러난다. */
    it("🔴 일본어로 렌더된다", () => {
        renderSummary({}, "ja");

        expect(screen.getByText("ご注文内容")).toBeInTheDocument();
        expect(screen.getByText("商品の小計")).toBeInTheDocument();
        expect(screen.getByText("合計")).toBeInTheDocument();
    });

    it("영어 원문이 남아 있지 않다", () => {
        renderSummary({}, "ja");

        expect(screen.queryByText("order Summary")).not.toBeInTheDocument();
        expect(screen.queryByText("Subtotal")).not.toBeInTheDocument();
        expect(screen.queryByText("Continue")).not.toBeInTheDocument();
    });

    it("배송비가 0이면 무료 배송이라고 한다", () => {
        renderSummary({ shippingFee: 0 });
        expect(screen.getByText("무료 배송")).toBeInTheDocument();
    });

    /** 🐛 예전에는 CartPage 가 toFixed 의 문자열 "0.00" 을 넘겨 `=== 0` 이 늘 거짓 — "배송비 0.00$" 로 보였다. */
    it("배송비가 문자열 \"0.00\" 으로 와도 무료 배송이다", () => {
        renderSummary({ shippingFee: "0.00" });
        expect(screen.getByText("무료 배송")).toBeInTheDocument();
    });

    it("배송비가 있으면 금액을 보여준다 — {{amount}} 가 남으면 안 된다", () => {
        renderSummary({ shippingFee: 3000 }, "ja");

        const fee = screen.getByText("送料 $3,000.00");
        expect(fee).toBeInTheDocument();
        expect(fee.textContent).not.toContain("{{amount}}");
    });

    /** 고른 상품이 없으면 다음 단계로 갈 수 없다 — 번역과 무관한 동작이지만 함께 고정한다. */
    it("고른 상품이 없으면 진행 버튼이 잠긴다", () => {
        renderSummary({ selected: [] });

        expect(screen.getByRole("button", { name: /주문서로 이동/ })).toBeDisabled();
    });
});

describe("빈 장바구니", () => {
    /** Empty 는 <Link> 를 쓰므로 라우터 컨텍스트가 필요하다. */
    const renderEmpty = (lang) =>
        render(withI18n(<MemoryRouter><Empty /></MemoryRouter>, lang));

    it("한국어·일본어로 안내한다", () => {
        renderEmpty("ko");
        expect(screen.getByText("장바구니가 비어 있습니다")).toBeInTheDocument();

        renderEmpty("ja");
        expect(screen.getByText("カートは空です")).toBeInTheDocument();
    });

    it("쇼핑하러 갈 길을 준다 — 막다른 화면이 되지 않게", () => {
        renderEmpty("ja");

        expect(screen.getByRole("link")).toHaveAttribute("href", "/browse");
        expect(screen.getByRole("button", { name: /買い物を続ける/ })).toBeInTheDocument();
    });
});
