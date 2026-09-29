import { loader } from "pages/Product";
import { getRequest } from "util/api";

jest.mock("util/api", () => ({ getRequest: jest.fn() }));
// jsdom 에는 fetch 의 Response 가 없다 — redirect 가 무엇을 가리키는지만 본다.
jest.mock("react-router-dom", () => ({ ...jest.requireActual("react-router-dom"), redirect: (to) => ({ redirectTo: to }) }));
jest.mock("pages/header/Header", () => () => null);
jest.mock("components/product/ProductPage", () => () => null);

const detail = {
    id: "1", name: "티셔츠", slug: "tee",
    skus: [{
        sku: "T-1", discount: 0, images: [], color: { id: "5" },
        // 서버가 이미 다른 주문의 결제 전 예약을 뺐다 — M 은 3 중 2 가 잡혀 1, L 은 잡힌 것이 없어 onHold 가 없다(docs/59 §5).
        sizes: [{ size: "M", quantity: 1, onHold: 2, price: 10 }, { size: "L", quantity: 4, price: 12 }],
    }],
};

describe("상품 상세 loader — 남은 수량과 결제 중인 수", () => {
    beforeEach(() => getRequest.mockResolvedValue({ data: detail }));

    it("고른 사이즈의 남은 수량과 잡혀 있는 수를 넘긴다", async () => {
        const p = await loader({ request: { url: "http://x/product/tee?style=0&size=0" }, params: { slug: "tee" } });
        expect(p.quantity).toBe(1);
        expect(p.onHold).toBe(2);
    });

    it("잡힌 것이 없으면(서버가 onHold 를 싣지 않았다) 0", async () => {
        const p = await loader({ request: { url: "http://x/product/tee?style=0&size=1" }, params: { slug: "tee" } });
        expect(p.quantity).toBe(4);
        expect(p.onHold).toBe(0);
    });
});

describe("상품 상세 loader — 사이즈 없는 상품", () => {
    const oil = {
        id: "1", name: "Italian Olive Oil", slug: "oil",
        skus: [{ sku: "FOO-1", discount: 0, images: [], color: { id: "1" },
            sizes: [{ size: "", quantity: 63, price: 37.38 }, { size: "", quantity: 14, price: 8.42 }] }],
    };

    it("이름이 전부 비면 sizeless — 첫 행의 가격·수량, 리뷰에 고를 사이즈 없음", async () => {
        getRequest.mockResolvedValue({ data: oil });
        const p = await loader({ request: { url: "http://x/product/oil?style=0" }, params: { slug: "oil" } });
        expect(p.sizeless).toBe(true);
        expect(p.quantity).toBe(63);
        expect(p.priceBefore).toBe(37.38);
        expect(p.allSizes).toEqual([]);
    });

    it("사이즈가 있으면 sizeless 가 아니다", async () => {
        getRequest.mockResolvedValue({ data: detail });
        const p = await loader({ request: { url: "http://x/product/tee?style=0" }, params: { slug: "tee" } });
        expect(p.sizeless).toBe(false);
        expect(p.allSizes.map((s) => s.size)).toEqual(expect.arrayContaining(["M", "L"]));
    });
});

describe("상품 상세 loader — 상품 id 로 들어오면", () => {
    it("slug 로 들어오면 그대로 그린다", async () => {
        getRequest.mockResolvedValue({ data: { ...detail, slug: "tee" } });
        const p = await loader({ request: { url: "http://x/product/tee?style=0" }, params: { slug: "tee" } });
        expect(p.redirectTo).toBeUndefined();
        expect(p.slug).toBe("tee");
    });

    it("🔴 slug 주소로 바꾼다(쿼리는 그대로)", async () => {
        getRequest.mockResolvedValue({ data: { ...detail, slug: "tee" } });
        const res = await loader({ request: { url: "http://x/product/1?style=0" }, params: { slug: "1" } });
        expect(res).toEqual({ redirectTo: "/product/tee?style=0" });
    });
});

describe("상품 상세 loader — 사이즈 단위 SKU (docs/65)", () => {
    const shoe = {
        id: "3", name: "러닝화", slug: "run",
        skus: [{ sku: "RUN-BLK", discount: 0, images: [], color: { id: "5" },
            sizes: [{ size: "270", skuCode: "RUN-BLK-270", quantity: 1, price: 100 },
                    { size: "280", skuCode: "RUN-BLK-280", quantity: 1, price: 100 }] }],
    };

    it("SKU 는 고른 사이즈 줄의 코드다 — 스타일 코드는 따로", async () => {
        getRequest.mockResolvedValue({ data: shoe });
        const p = await loader({ request: { url: "http://x/product/run?style=0&size=1" }, params: { slug: "run" } });
        expect(p.sku).toBe("RUN-BLK-280");
        expect(p.styleCode).toBe("RUN-BLK");
    });

    it("코드가 생기기 전의 응답(캐시)이면 스타일 코드로 대신한다", async () => {
        getRequest.mockResolvedValue({ data: detail });
        const p = await loader({ request: { url: "http://x/product/tee?style=0&size=0" }, params: { slug: "tee" } });
        expect(p.sku).toBe("T-1");
    });
});
