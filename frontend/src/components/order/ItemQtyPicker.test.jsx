import { fireEvent, render, screen } from "@testing-library/react";

import ItemQtyPicker, { anySelected, toItems } from "components/order/ItemQtyPicker";

const lines = [
    { id: 101, name: "티셔츠", size: "M", max: 2 },
    { id: 102, name: "바지", size: "L", max: 1 },
    { id: 103, name: "모자", size: null, max: 0 },
];

/** 부분 환불·반품의 공통 조각. 기본은 "남은 전부" 라 손대지 않으면 서버 기본(items 없음)과 같다. */
describe("ItemQtyPicker", () => {
    it("전부 남은 수량이면 items 는 null — 서버가 남은 전부로 본다", () => {
        expect(toItems(lines, {})).toBeNull();
        expect(anySelected(lines, {})).toBe(true);
    });

    it("하나라도 줄이면 0 을 뺀 목록을 보낸다", () => {
        expect(toItems(lines, { 101: 1, 102: 0 })).toEqual([{ orderProductId: 101, qty: 1 }]);
    });

    it("전부 0 이면 아무것도 고르지 않은 것이다", () => {
        expect(anySelected(lines, { 101: 0, 102: 0 })).toBe(false);
    });

    it("select 는 0…max 이고, 남은 수량 0 인 줄은 잠긴다", () => {
        const onChange = jest.fn();
        render(<ItemQtyPicker lines={lines} value={{}} onChange={onChange} label="환불 수량" />);

        const shirt = screen.getByLabelText("환불 수량 티셔츠");
        expect(shirt).toHaveValue("2");
        expect(shirt.querySelectorAll("option")).toHaveLength(3);
        expect(screen.getByLabelText("환불 수량 모자")).toBeDisabled();

        fireEvent.change(shirt, { target: { value: "1" } });
        expect(onChange).toHaveBeenCalledWith({ 101: 1 });
    });
});
