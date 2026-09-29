import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import Reviews from "components/product/reviews/Reviews";
import { withI18n } from "i18n/testing";
import { queryClient, toggleReviewLike } from "util/api";

jest.mock("store/AuthContext", () => ({ useAuthContext: () => ({ token: "t" }) }));
jest.mock("components/product/reviews/Review", () => () => null);
jest.mock("react-hot-toast", () => ({ __esModule: true, default: { error: jest.fn() } }));
jest.mock("util/api", () => {
    const actual = jest.requireActual("util/api");
    return { ...actual, toggleReviewLike: jest.fn() };
});

const review = (likeCount, over = {}) => ({
    reviewId: "891579158660068589", rating: 5, review: "좋아요", reviewedBy: { name: "yoon", image: null },
    likeCount, likedByMe: false, ...over,
});
const product = { id: "1", rating: 5, ratings: [], allSizes: [], colors: [] };

const renderReviews = (reviews, updateReviews = jest.fn()) => {
    const ui = (list) => withI18n(
        <MemoryRouter>
            <Reviews product={product} num_reviews={list.length} userReviews={list} updateReviews={updateReviews} />
        </MemoryRouter>
    );
    const view = render(ui(reviews));
    return { rerender: (list) => view.rerender(ui(list)), updateReviews };
};

beforeEach(() => jest.clearAllMocks());

/**
 * 리뷰 목록은 주기적으로 다시 읽는다(useFetchReviews) — 다른 사람이 누른 좋아요·새 리뷰가 새로고침 없이 보인다.
 * 🐛 예전에는 처음 받은 목록을 복사해 둔 뒤로 다시 보지 않았다.
 */
describe("리뷰 — 다시 읽은 목록을 반영", () => {
    it("🔴 다른 사람이 누른 좋아요(새로 읽은 목록)가 화면에 반영된다", () => {
        const { rerender } = renderReviews([review(0)]);
        expect(screen.getAllByRole("button", { name: /좋아요|いいね/ })[0]).toHaveTextContent("0");

        rerender([review(3)]);

        expect(screen.getAllByRole("button", { name: /좋아요|いいね/ })[0]).toHaveTextContent("3");
    });

    it("🔴 내가 누른 것이 확정되면 목록을 바로 다시 읽는다 — 늦게 온 옛 목록이 덮지 않게", async () => {
        toggleReviewLike.mockResolvedValue({ reviewId: "891579158660068589", likeCount: 1, likedByMe: true });
        const { updateReviews } = renderReviews([review(0)]);

        fireEvent.click(screen.getAllByRole("button", { name: /좋아요|いいね/ })[0]);

        await waitFor(() => expect(updateReviews).toHaveBeenCalledTimes(1));
        expect(toggleReviewLike).toHaveBeenCalledWith("891579158660068589");
    });

    it("실패하면(되돌릴 때) 다시 읽지 않는다", async () => {
        toggleReviewLike.mockRejectedValue({ response: { status: 404 } });
        const { updateReviews } = renderReviews([review(0)]);

        await act(async () => {
            fireEvent.click(screen.getAllByRole("button", { name: /좋아요|いいね/ })[0]);
        });

        expect(updateReviews).not.toHaveBeenCalled();
    });
});

describe("주기적 읽기의 실패는 조용히", () => {
    const toast = jest.requireMock("react-hot-toast").default;
    const onError = queryClient.getQueryCache().config.onError;

    it("🔴 silentRefetch 표시가 있으면 알림을 띄우지 않는다 — 30초마다 뜨지 않게", () => {
        onError(new Error("Network Error"), { state: { data: { reviews: [] } }, meta: { silentRefetch: true } });
        expect(toast.error).not.toHaveBeenCalled();
    });

    it("표시가 없는 조회는 예전처럼 알린다", () => {
        onError(new Error("Network Error"), { state: { data: { reviews: [] } }, meta: undefined });
        expect(toast.error).toHaveBeenCalledTimes(1);
    });
});
