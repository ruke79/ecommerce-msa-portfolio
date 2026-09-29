import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import ReviewCard from "components/product/reviews/ReviewCard";
import { withI18n } from "i18n/testing";

jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ token: null }) }));
jest.mock("util/api", () => ({ __esModule: true, default: {}, toggleReviewLike: jest.fn() }));

const renderCard = (review) => render(withI18n(
    <MemoryRouter>
        <ReviewCard review={{ reviewId: 1, rating: 5, review: "좋아요", reviewedBy: { name: "a", image: "" }, ...review }} />
    </MemoryRouter>
));

/** 사이즈 없는 상품의 리뷰는 사이즈·착용감이 비어 있다 — "사이즈:"·"착용감:" 만 찍지 않는다. */
describe("리뷰 카드 — 사이즈·착용감", () => {
    it("있으면 보여 준다", () => {
        renderCard({ size: "M", fit: "True to size" });
        expect(screen.getAllByText(/사이즈: M/).length).toBeGreaterThan(0);
        expect(screen.getAllByText(/착용감:/).length).toBeGreaterThan(0);
    });

    it("🔴 비어 있으면 줄 자체를 그리지 않는다", () => {
        renderCard({ size: "", fit: "" });
        expect(screen.queryByText(/사이즈:/)).toBeNull();
        expect(screen.queryByText(/착용감:/)).toBeNull();
    });
});

/** 🐛 작성자 사진 — 사진을 올리지 않은 회원(대부분)은 image 가 null 이라 깨진 아이콘이 찍혔다. */
describe("리뷰 카드 — 작성자 사진", () => {
    it("🔴 사진이 없으면 이미지 대신 이름의 첫 글자", () => {
        const { container } = renderCard({ reviewedBy: { name: "yoon", image: null } });
        expect(container.querySelector("img")).toBeNull();
        expect(screen.getByText("Y")).toBeInTheDocument();
        expect(screen.getByText("yoon")).toBeInTheDocument();
    });

    it("사진이 있으면 그리고, 받아지지 않으면(지운 파일) 첫 글자로 바꾼다", () => {
        const { container } = renderCard({ reviewedBy: { name: "yoon", image: "https://img.example/gone.png" } });
        const img = container.querySelector("img");
        expect(img).toHaveAttribute("src", "https://img.example/gone.png");

        fireEvent.error(img);

        expect(container.querySelector("img")).toBeNull();
        expect(screen.getByText("Y")).toBeInTheDocument();
    });
});
