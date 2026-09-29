import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import Stock from "pages/admin/Stock";
import { adminListStock, adminSetStock } from "api/restockApi";
import { withI18n } from "i18n/testing";

jest.mock("api/restockApi");

const row = (over = {}) => ({
    sizeId: 77, productId: 10, name: "티셔츠", slug: "tee", colorId: 3, color: "black", size: "M", quantity: 0,
    ...over,
});

/**
 * 관리자 재고 (docs/53) — 품절 옵션을 채운다. 보내는 것은 사이즈 id 와 수량뿐이고, 저장 뒤에는 서버에서 다시 읽는다.
 */
describe("재고 관리", () => {
    beforeEach(() => {
        jest.clearAllMocks();
        adminListStock.mockResolvedValue([row()]);
        adminSetStock.mockResolvedValue(row({ quantity: 12 }));
    });

    it("기본은 품절만 — 품절 옵션을 '품절' 로 보여 준다", async () => {
        render(withI18n(<Stock />));

        expect(await screen.findByText("black / M")).toBeInTheDocument();
        expect(adminListStock).toHaveBeenCalledWith({ soldOutOnly: true });
        expect(screen.getByText("품절")).toBeInTheDocument();
    });

    it("🔴 채우면 그 값으로 정하고, 0 에서 채웠으면 재입고 알림이 간다고 말한 뒤 다시 읽는다", async () => {
        render(withI18n(<Stock />));
        fireEvent.change(await screen.findByLabelText("티셔츠 black / M 새 재고"), { target: { value: "12" } });
        fireEvent.click(screen.getByRole("button", { name: "저장" }));

        await waitFor(() => expect(adminSetStock).toHaveBeenCalledWith(77, 12));
        expect(await screen.findByRole("status")).toHaveTextContent("재입고 알림");
        expect(adminListStock).toHaveBeenCalledTimes(2);
    });

    it("음수·빈 값은 보내지 않는다", async () => {
        render(withI18n(<Stock />));
        fireEvent.change(await screen.findByLabelText("티셔츠 black / M 새 재고"), { target: { value: "-1" } });
        fireEvent.click(screen.getByRole("button", { name: "저장" }));

        expect(await screen.findByRole("alert")).toHaveTextContent("0 이상의 정수");
        expect(adminSetStock).not.toHaveBeenCalled();
    });

    it("전체 보기로 바꾸면 품절 아닌 옵션도 읽는다", async () => {
        render(withI18n(<Stock />));
        await screen.findByText("black / M");
        fireEvent.change(screen.getByLabelText("보기"), { target: { value: "all" } });

        await waitFor(() => expect(adminListStock).toHaveBeenLastCalledWith({ soldOutOnly: false }));
    });

    it("옵션 아래에 그 줄의 SKU 를 보여 준다 — 이 행이 곧 재고 단위다", async () => {
        adminListStock.mockResolvedValue([row({ skuCode: "TEE-BLK-M" })]);
        render(withI18n(<Stock />));

        expect(await screen.findByText("TEE-BLK-M")).toBeInTheDocument();
    });
});
