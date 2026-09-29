import { render as rtlRender, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import Product from "components/checkout/ChekoutProduct";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

/** 서버의 checkout 응답 모양(CartProductDto) — 색상은 {@code color.colorImage} 다. */
const cart = {
    cartTotal: 129.98,
    products: [
        {
            id: "line-1", productId: "p-1", name: "Trail Running Shoe", image: "https://cdn/shoe.png",
            size: "270", qty: 2, price: "59.99",
            color: { id: "c-1", color: "black", colorImage: "https://cdn/black.png" },
        },
        {
            id: "line-2", productId: "p-2", name: "Cap", image: null, size: "", qty: 1, price: "10.00",
            color: null,
        },
    ],
};

const renderIt = (c = cart) => render(<MemoryRouter><Product cart={c} /></MemoryRouter>);

/**
 * 🐛 색상 칩이 {@code color.image} 를 읽어 {@code src={undefined}} 가 됐고, 브라우저는 그런
 * {@code <img>} 를 빈 칸이 아니라 <b>alt(상품 이름)를 글자로</b> 그린다 — 칩마다 이름이
 * 배경처럼 겹쳐 보였다.
 */
describe("결제 화면의 장바구니 요약", () => {
    it("상품 이미지와 색상 칩은 DTO 의 image · color.colorImage 를 쓴다", () => {
        renderIt();
        expect(screen.getByAltText("Trail Running Shoe")).toHaveAttribute("src", "https://cdn/shoe.png");
        expect(screen.getByAltText("black")).toHaveAttribute("src", "https://cdn/black.png");
    });

    it("이미지가 없는 줄에는 <img> 를 그리지 않는다 — alt 가 글자로 새는 것을 막는다", () => {
        renderIt();
        const imgs = screen.getAllByRole("img");
        expect(imgs.map((i) => i.getAttribute("src"))).toEqual(["https://cdn/shoe.png", "https://cdn/black.png"]);
        expect(imgs.every((i) => i.getAttribute("src"))).toBe(true);
    });

    it("줄 금액과 소계를 상점 통화로 보여 준다", () => {
        renderIt();
        // 한국어 화면(ko-KR)의 달러 표기는 "US$" 다 — 통화 표기는 앱 언어를 따른다(useMoney).
        expect(screen.getByText("US$119.98")).toBeInTheDocument();
        expect(screen.getByText("US$129.98")).toBeInTheDocument();
        expect(screen.getByText("2개")).toBeInTheDocument();
    });

    /** 🔴 일본어 화면에 영어("Cart"·"items"·"Subtotal")가 남으면 안 된다. */
    it("일본어로 그리면 제목·개수·소계가 일본어다", () => {
        rtlRender(withI18n(<MemoryRouter><Product cart={cart} /></MemoryRouter>, "ja"));
        expect(screen.getByRole("heading", { name: "カート" })).toBeInTheDocument();
        expect(screen.getByText("2点")).toBeInTheDocument();
        expect(screen.getByText(/小計:/)).toBeInTheDocument();
        expect(screen.queryByText(/items|Subtotal/)).not.toBeInTheDocument();
    });

    it("응답이 아직 없으면 빈 목록으로 그린다", () => {
        renderIt(null);
        expect(screen.getByText("0개")).toBeInTheDocument();
    });
});

describe("체크아웃 상품 링크", () => {
    const { productHref } = require("components/checkout/ChekoutProduct");

    test("🔴 이름이 아니라 상품 id 로, 담은 스타일로 간다", () => {
        expect(productHref({ productId: "1", name: "Italian Olive Oil", style: 1, size: "" }))
            .toBe("/product/1?style=1");
    });

    test("상품 id 가 없는 옛 장바구니 사본은 예전처럼 이름으로", () => {
        expect(productHref({ name: "Italian Olive Oil" })).toBe("/product/Italian-Olive-Oil?style=0");
    });
});
