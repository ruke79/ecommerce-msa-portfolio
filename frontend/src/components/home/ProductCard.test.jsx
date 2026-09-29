import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import ProductCard from "components/home/ProductCard";
import { withI18n } from "i18n/testing";

jest.mock("components/home/ProductSwiper", () => () => <div data-testid="swiper" />);

/**
 * 🔴 목록 카드(검색·홈·기획전)의 가격은 상품 화면과 같아야 한다.
 *
 * <p>예전에는 카드가 사이즈의 <b>정가</b>를 "USD100$" 로, 상품 화면이 <b>할인가</b>를 보여 줬다 — 같은 상품이
 * 검색에서는 $100, 들어가면 $70 이었다. 카드에는 "-30%" 배지까지 붙은 채로. 서버는 두 화면에 같은 값을 보낸다.
 */
describe("ProductCard — 목록의 가격", () => {
    const product = (discount, prices) => ({
        name: "Tee",
        slug: "tee",
        skus: [{ discount, images: [], color: { color: "#000" }, sizes: prices.map((price) => ({ price })) }],
    });

    const renderCard = (p, lang = "ko") =>
        render(withI18n(<MemoryRouter><ProductCard product={p} /></MemoryRouter>, lang));

    it("🔴 할인가를 보여 주고 정가는 줄을 긋는다 — 상품 화면과 같은 값", () => {
        renderCard(product(30, [100]));

        expect(screen.getByText("US$70.00")).toBeInTheDocument();
        expect(screen.getByText("US$100.00")).toHaveClass("line-through");
        expect(screen.getByText("-30%")).toBeInTheDocument();
    });

    it("가격이 여럿이면 할인가 범위", () => {
        renderCard(product(10, [120, 100]));

        expect(screen.getByText("US$90.00 – US$108.00")).toBeInTheDocument();
    });

    it("할인이 없으면 정가 하나 — 줄 그은 가격이 없다", () => {
        const { container } = renderCard(product(0, [59.98]), "ja");

        expect(screen.getByText("$59.98")).toBeInTheDocument();
        expect(container.querySelector(".line-through")).toBeNull();
    });

    it("통화를 손으로 붙이지 않는다 — 예전의 USD…$ 표기가 없다", () => {
        const { container } = renderCard(product(30, [100]));

        expect(container.textContent).not.toMatch(/USD|\d\$/);
    });
});

/**
 * 목록 카드의 재고 배지 (docs/59 §5) — 서버가 지금 수량 − 결제 전 예약을 보낸다.
 */
describe("ProductCard — 재고 배지", () => {
    const withSizes = (sizes, extra = {}) => ({
        name: "Tee", slug: "tee", ...extra,
        skus: [{ discount: 0, images: [], color: { color: "#000" }, sizes: sizes.map((s) => ({ price: 10, ...s })) }],
    });
    const renderCard = (p, lang = "ko") =>
        render(withI18n(<MemoryRouter><ProductCard product={p} /></MemoryRouter>, lang));

    it("남은 게 없고 누가 결제 중이면 '결제 진행 중' — 품절이 아니다", () => {
        renderCard(withSizes([{ quantity: 0, onHold: 1 }]));
        expect(screen.getByTestId("stock-badge")).toHaveTextContent("결제 진행 중");
        expect(screen.getByTestId("stock-badge")).toHaveAttribute("data-kind", "on_hold");
    });

    it("품절이면 '품절'", () => {
        renderCard(withSizes([{ quantity: 0 }, { quantity: 0 }]));
        expect(screen.getByTestId("stock-badge")).toHaveTextContent("품절");
    });

    it("얼마 안 남았으면 몇 개인지 — 일본어도", () => {
        renderCard(withSizes([{ quantity: 1 }, { quantity: 1 }]), "ja");
        expect(screen.getByTestId("stock-badge")).toHaveTextContent("残り2点");
    });

    it("넉넉하면 배지가 없다", () => {
        renderCard(withSizes([{ quantity: 10 }]));
        expect(screen.queryByTestId("stock-badge")).toBeNull();
    });
});

/**
 * 🐛 검색의 가격 필터는 상품의 <b>어느 스타일이든</b> 범위에 들면 상품을 준다. 카드가 첫 스타일만 보여 줘서 "$50~$100" 결과에
 * "$15~$29" 카드가 줄지어 보였다 — 맞은 것은 둘째 스타일이었다.
 */
describe("ProductCard — 검색 가격대에 맞는 스타일부터", () => {
    const threeStyles = {
        name: "Tote Bag",
        slug: "tote",
        skus: [
            { discount: 0, images: [], color: { color: "#111" }, sizes: [{ price: 15 }, { price: 29 }] },
            { discount: 0, images: [], color: { color: "#222" }, sizes: [{ price: 72 }] },
            { discount: 0, images: [], color: { color: "#333" }, sizes: [{ price: 120 }] },
        ],
    };
    const renderCard = (priceFilter) =>
        render(withI18n(<MemoryRouter><ProductCard product={threeStyles} priceFilter={priceFilter} /></MemoryRouter>));

    it("🔴 $50~$100 이면 그 범위의 스타일(둘째) 가격을 보여 준다", () => {
        renderCard({ low: "50", high: "100" });
        expect(screen.getByText("US$72.00")).toBeInTheDocument();
        expect(screen.queryByText(/US\$15\.00/)).toBeNull();
    });

    it("상한만·하한만(열린 범위)도 — 500 이상이면 해당 없음 → 첫 스타일, 100 이상이면 셋째", () => {
        const { unmount } = renderCard({ low: "100", high: "" });
        expect(screen.getByText("US$120.00")).toBeInTheDocument();
        unmount();
        renderCard({ low: "500", high: "" });
        expect(screen.getByText("US$15.00 – US$29.00")).toBeInTheDocument();
    });

    it("필터가 없으면 예전처럼 첫 스타일", () => {
        renderCard(undefined);
        expect(screen.getByText("US$15.00 – US$29.00")).toBeInTheDocument();
    });
});
