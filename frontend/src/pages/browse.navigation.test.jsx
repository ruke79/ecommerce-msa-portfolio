import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";

import Browse, { loader } from "pages/browse";
import { withI18n } from "i18n/testing";

// jsdom 에는 fetch 의 Request 가 없다 — 데이터 라우터는 loader 에 넘길 요청(주소 · 신호)만 만든다. 그만큼만 채운다.
if (typeof global.Request === "undefined") {
    global.Request = class {
        constructor(input, init = {}) {
            this.url = String(input);
            this.method = init.method ?? "GET";
            this.signal = init.signal ?? new AbortController().signal;
            this.headers = new Headers(init.headers);
        }
    };
}
if (typeof global.Headers === "undefined") {
    global.Headers = class { constructor() { this.m = new Map(); } get(k) { return this.m.get(k) ?? null; } set(k, v) { this.m.set(k, v); } };
}

// 화면 바깥(머리글 · 카드 · 무한 스크롤 감시)은 덜어 낸다 — 이 테스트가 보는 것은 "어느 조건의 결과가 그려지는가" 하나다.
jest.mock("pages/header/Header", () => () => null);
jest.mock("components/home/ProductCard", () => ({ product }) => <div data-testid="card">{product.name}</div>);
jest.mock("hook/hooks", () => ({ useInfiniteScroll: () => ({ current: null }) }));
jest.mock("util/api", () => ({ getRequest: jest.fn() }));
const { getRequest } = require("util/api");

const CATEGORIES = [
    { id: "1", name: "푸드" },
    { id: "2", name: "책" },
];
const PRODUCTS = {
    "": ["푸드-새것", "책-새것", "가전-새것"],
    1: ["푸드-라면", "푸드-과자"],
    2: ["책-소설", "책-만화"],
};

/** 카테고리마다 다른 결과를 늦게(서버처럼) 돌려준다. */
function serve() {
    getRequest.mockImplementation((_url, { params }) => {
        const key = typeof params.category === "string" ? params.category : "";
        const names = PRODUCTS[key];
        return new Promise((resolve) => setTimeout(() => resolve({
            status: 200,
            data: {
                product: { content: names.map((name, i) => ({ id: `${key}-${i}`, name, skus: [] })) },
                totalProducts: names.length,
                categories: CATEGORIES, subCategories: [], details: [], brandsDB: [], sizes: [], colors: [],
            },
        }), 30));
    });
}

function renderAt(url) {
    const router = createMemoryRouter([{ path: "/browse", element: <Browse />, loader }], { initialEntries: [url] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(withI18n(
        <QueryClientProvider client={client}>
            <RouterProvider router={router} />
        </QueryClientProvider>,
    ));
    return router;
}

const cards = () => screen.queryAllByTestId("card").map((c) => c.textContent);
// 왼쪽 메뉴의 카테고리 — 이름은 위쪽 경로 표시 · 버튼 줄에도 나오니 마지막(메뉴)을 누른다.
const menu = (name) => screen.getAllByText(name).at(-1);

beforeEach(() => {
    jest.clearAllMocks();
    serve();
});

/**
 * 🐛 2026-10-24 운영: "전체 클리어 했는데 푸드만 보이고, 푸드를 누르면 책만 보인다" — 화면이 언제나 <b>바로 앞 조건의 결과</b>를 그렸다.
 *
 * <p>필터 핸들러가 {@code useSearchParams()} 의 객체를 제자리에서 바꾼 뒤 이동했다. 그 객체는 지금 주소의 것이라, 이동(loader)이
 * 끝나기 전 다시 그릴 때(이동 중 표시) 쿼리 키는 <b>새 조건</b>이 되는데 {@code initialData} 는 아직 <b>앞 조건</b>의 loader
 * 결과다 — 새 키에 앞 결과가 담기고 staleTime(60초) 동안 그대로 보였다.
 *
 * <p>[JA] フィルター操作で useSearchParams() のオブジェクトをその場で書き換えてから遷移していた。遷移中の再描画でクエリキーは
 * 新しい条件、initialData はまだ前の条件の結果 — 新しいキーに前の結果が入り、60 秒そのまま表示された。
 */
describe("browse — 필터를 바꾸면 그 조건의 결과를 그린다", () => {
    it("🔴 카테고리를 누르면 그 카테고리, 다른 것을 누르면 그것, 전체 해제면 전체", async () => {
        renderAt("/browse");
        await waitFor(() => expect(cards()).toEqual(PRODUCTS[""]));

        fireEvent.click(menu("푸드"));
        await waitFor(() => expect(getRequest).toHaveBeenCalledTimes(2));
        await act(() => new Promise((r) => setTimeout(r, 80)));
        expect(cards()).toEqual(PRODUCTS[1]);

        fireEvent.click(menu("책"));
        await act(() => new Promise((r) => setTimeout(r, 80)));
        expect(cards()).toEqual(PRODUCTS[2]);

        fireEvent.click(screen.getByRole("button", { name: /전체/ }));
        await act(() => new Promise((r) => setTimeout(r, 80)));
        expect(cards()).toEqual(PRODUCTS[""]);
    });

    it("같은 카테고리를 다시 누르면(해제) 전체", async () => {
        const router = renderAt("/browse?category=1");
        await waitFor(() => expect(cards()).toEqual(PRODUCTS[1]));

        fireEvent.click(menu("푸드"));
        await act(() => new Promise((r) => setTimeout(r, 80)));
        expect(router.state.location.search).toBe("");
        expect(cards()).toEqual(PRODUCTS[""]);
    });
});
