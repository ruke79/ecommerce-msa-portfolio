import { hasSizes, sizeLabel } from "./sizes";

describe("사이즈 없는 상품", () => {
    test("이름이 전부 비어 있으면 사이즈가 없다", () => {
        expect(hasSizes([{ size: "" }, { size: "  " }, { size: null }])).toBe(false);
        expect(hasSizes([])).toBe(false);
        expect(hasSizes(undefined)).toBe(false);
    });

    test("이름이 하나라도 있으면 사이즈가 있다", () => {
        expect(hasSizes([{ size: "" }, { size: "M" }])).toBe(true);
    });

    test("이름은 앞뒤 공백을 뺀다", () => {
        expect(sizeLabel({ size: " L " })).toBe("L");
        expect(sizeLabel(undefined)).toBe("");
    });
});
