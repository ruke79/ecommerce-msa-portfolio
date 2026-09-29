import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import AddReview from "components/product/reviews/Review";
import { withI18n } from "i18n/testing";
import { deleteRequest, postRequest } from "util/api";

const mockDispatch = jest.fn();
jest.mock("react-redux", () => ({ useDispatch: () => mockDispatch, useSelector: () => ({ show: false, msgs: [] }) }));
jest.mock("components/DialogModal", () => () => null);
jest.mock("util/api", () => ({ __esModule: true, default: {}, postRequest: jest.fn(), deleteRequest: jest.fn() }));
jest.mock("util/dataURItoBlob", () => () => new Blob());

const product = {
    id: "7",
    allSizes: [{ size: "M" }, { size: "L" }],
    colors: [{ id: "5", color: "black", colorImage: "/black.png" }, { id: "6", color: "white", colorImage: "/white.png" }],
};

const renderForm = (props = {}) => {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    return render(withI18n(
        <QueryClientProvider client={client}>
            <AddReview product={product} myReview={null} setReviews={jest.fn()} updateReviews={jest.fn()} {...props} />
        </QueryClientProvider>
    ));
};

const dialogs = () => mockDispatch.mock.calls.map(([a]) => a).filter((a) => a?.type?.includes("showDialog")).map((a) => a.payload);

beforeEach(() => {
    jest.clearAllMocks();
    window.confirm = jest.fn(() => true);
});

/**
 * 리뷰 쓰기·지우기 — 내 리뷰가 없을 때 삭제가 "성공" 으로 뜨던 것, 오류에 화면이 죽던 것, 스타일 그림이 비던 것.
 */
describe("리뷰 작성 폼", () => {
    it("🔴 내 리뷰가 없으면 삭제 버튼이 없다", () => {
        renderForm();
        expect(screen.queryByRole("button", { name: "리뷰 삭제" })).toBeNull();
        expect(screen.getByRole("button", { name: "리뷰 등록" })).toBeInTheDocument();
    });

    it("내 리뷰가 있으면 '수정' 과 '삭제' — 지우기 전에 묻고, 취소하면 보내지 않는다", () => {
        window.confirm = jest.fn(() => false);
        renderForm({ myReview: { reviewId: 1, mine: true } });

        expect(screen.getByRole("button", { name: "리뷰 수정" })).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "리뷰 삭제" }));

        expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining("삭제할까요"));
        expect(deleteRequest).not.toHaveBeenCalled();
    });

    it("🔴 서버가 거절하면 화면이 죽지 않고 사유를 보여 준다 — '삭제할 리뷰가 없습니다'", async () => {
        deleteRequest.mockRejectedValue({ response: { status: 404, data: { message: "삭제할 리뷰가 없습니다." } } });
        renderForm({ myReview: { reviewId: 1, mine: true } });

        fireEvent.click(screen.getByRole("button", { name: "리뷰 삭제" }));

        await waitFor(() => expect(dialogs()).toHaveLength(1));
        expect(dialogs()[0].msgs[0]).toEqual({ msg: "삭제할 리뷰가 없습니다.", type: "error" });
        expect(screen.getByRole("button", { name: "리뷰 삭제" })).toBeEnabled();
    });

    it("🔴 스타일은 서버가 읽는 {color, image} 로 보낸다 — 상품 색상의 그림은 colorImage 다", async () => {
        postRequest.mockResolvedValue({ data: [] });
        renderForm();

        fireEvent.click(screen.getByTestId("review-select-size"));
        fireEvent.click(screen.getByRole("option", { name: "L" }));
        fireEvent.click(screen.getByTestId("review-select-style"));
        fireEvent.click(screen.getByRole("option", { name: /white/ }));
        fireEvent.click(screen.getByTestId("review-select-fit"));
        fireEvent.click(screen.getByRole("option", { name: "정사이즈" }));
        fireEvent.change(screen.getByPlaceholderText("리뷰를 작성해 주세요..."), { target: { value: "좋아요" } });
        fireEvent.click(screen.getAllByRole("radio")[9]);   // 별 5개
        fireEvent.click(screen.getByRole("button", { name: "리뷰 등록" }));

        await waitFor(() => expect(postRequest).toHaveBeenCalled());
        const form = postRequest.mock.calls[0][1];
        // jsdom 의 Blob 에는 text() 가 없다 — FileReader 로 읽는다
        const text = await new Promise((resolve) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.readAsText(form.get("review"));
        });
        const body = JSON.parse(text);
        expect(body).toMatchObject({ size: "L", fit: "True to size", style: { color: "white", image: "/white.png" } });
    });

    it("선택 목록은 고르면 닫힌다 — 예전에는 마우스가 벗어날 때만 닫혀 휴대폰에서 열린 채였다", () => {
        renderForm();
        fireEvent.click(screen.getByTestId("review-select-size"));
        expect(screen.getByRole("listbox")).toBeInTheDocument();

        fireEvent.click(screen.getByRole("option", { name: "M" }));

        expect(screen.queryByRole("listbox")).toBeNull();
        expect(screen.getByTestId("review-select-size")).toHaveTextContent("M");
    });

    it("빈 칸이 있으면 무엇이 빠졌는지 알려 주고 보내지 않는다", () => {
        renderForm();
        fireEvent.click(screen.getByRole("button", { name: "리뷰 등록" }));

        const msgs = dialogs()[0].msgs.map((m) => m.msg);
        expect(msgs).toEqual(expect.arrayContaining(["사이즈를 선택해 주세요.", "스타일을 선택해 주세요."]));
        expect(postRequest).not.toHaveBeenCalled();
    });

    it("🔴 사이즈 없는 상품은 사이즈·착용감을 묻지도 막지도 않는다 — 예전에는 고를 수 없어 리뷰를 쓸 수 없었다", async () => {
        postRequest.mockResolvedValue({ data: [] });
        renderForm({ product: { ...product, allSizes: [] } });

        expect(screen.queryByTestId("review-select-size")).toBeNull();
        expect(screen.queryByTestId("review-select-fit")).toBeNull();
        fireEvent.click(screen.getByTestId("review-select-style"));
        fireEvent.click(screen.getByRole("option", { name: /white/ }));
        fireEvent.change(screen.getByPlaceholderText("리뷰를 작성해 주세요..."), { target: { value: "맛있어요" } });
        fireEvent.click(screen.getAllByRole("radio")[9]);
        fireEvent.click(screen.getByRole("button", { name: "리뷰 등록" }));

        await waitFor(() => expect(postRequest).toHaveBeenCalled());
        const shown = dialogs().flatMap((d) => d.msgs.map((m) => m.msg));
        expect(shown).not.toContain("사이즈를 선택해 주세요.");
        expect(shown.some((m) => /착용감/.test(m))).toBe(false);
    });
});
