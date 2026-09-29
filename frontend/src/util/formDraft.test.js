import { saveDraft, loadDraft, clearDraft } from "util/formDraft";

const KEY = "test-draft";

describe("formDraft", () => {
  afterEach(() => localStorage.clear());

  it("텍스트 필드는 저장·복구된다", () => {
    saveDraft(KEY, { name: "긴 상품 이름", brand: "브랜드", color: { color: "#fff", image: "" } });

    const draft = loadDraft(KEY);
    expect(draft.name).toBe("긴 상품 이름");
    expect(draft.brand).toBe("브랜드");
  });

  it("이미지(base64)는 저장하지 않는다 — localStorage 쿼터(5MB)를 즉시 넘긴다", () => {
    const bigImage = "data:image/jpeg;base64," + "x".repeat(10000);
    saveDraft(KEY, {
      name: "상품",
      images: [bigImage, bigImage],
      color: { color: "#fff", image: bigImage },
    });

    const draft = loadDraft(KEY);
    expect(draft.images).toBeUndefined();
    expect(draft.color.image).toBe("");
    expect(draft.color.color).toBe("#fff");   // 색상값 자체는 지킨다
  });

  it("저장 실패(쿼터 초과)는 조용히 포기한다 — 초안은 편의 기능이지 게이트가 아니다", () => {
    const spy = jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("QuotaExceededError");
    });

    expect(() => saveDraft(KEY, { name: "x" })).not.toThrow();
    spy.mockRestore();
  });

  it("깨진 초안은 null — 복구가 폼을 터뜨리면 안 된다", () => {
    localStorage.setItem(KEY, "{broken json");
    expect(loadDraft(KEY)).toBeNull();
  });

  it("clear 후에는 아무것도 없다", () => {
    saveDraft(KEY, { name: "x" });
    clearDraft(KEY);
    expect(loadDraft(KEY)).toBeNull();
  });
});
