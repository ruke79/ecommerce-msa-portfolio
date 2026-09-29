import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// 이 테스트가 보는 것은 "언제 서버를 부르는가" 하나다. 폼 본체·배너는 관심 밖이므로
// 비운다(둘 다 자체 네트워크 호출을 갖고 있어 섞이면 횟수를 셀 수 없다).
jest.mock("components/admin/StorageHealthBanner", () => ({
  __esModule: true,
  default: () => null,
}));

// CreateProduct 대신 "카테고리 선택"과 "이름 입력"만 있는 최소 폼을 끼운다.
jest.mock("components/admin/CreateProduct", () => ({
  __esModule: true,
  default: ({ product, setProduct }) => (
    <div>
      <input
        aria-label="name"
        value={product.name}
        onChange={(e) => setProduct((p) => ({ ...p, name: e.target.value }))}
      />
      <button
        type="button"
        onClick={() => setProduct((p) => ({ ...p, category: "cat-1" }))}
      >
        select-category
      </button>
    </div>
  ),
}));

const mockLoadCategories = jest.fn();
jest.mock("components/admin/CreateCategory", () => ({
  loadCategories: (...a) => mockLoadCategories(...a),
}));

const mockApiGet = jest.fn();
const mockGetRequest = jest.fn();
jest.mock("util/api", () => ({
  __esModule: true,
  default: { get: (...a) => mockApiGet(...a) },
  getRequest: (...a) => mockGetRequest(...a),
}));

jest.mock("util/formDraft", () => ({
  loadDraft: () => null,
  saveDraft: jest.fn(),
  clearDraft: jest.fn(),
}));

const AdminProduct = require("pages/admin/Index").default;
const { withI18n } = require("i18n/testing");

const subcategoryCalls = () =>
  mockGetRequest.mock.calls.filter(([url]) => String(url).includes("subcategories"));

describe("관리자 상품 등록 화면 — 언제 서버를 부르는가", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockApiGet.mockResolvedValue({ data: [] });
    mockGetRequest.mockResolvedValue({ data: [] });
  });

  /**
   * 🐛 회귀 대상: **상품 이름을 한 글자 칠 때마다 API 가 나갔다.**
   *
   * 서브카테고리를 받는 effect 의 의존성이 `[product.category, product.name]` 이었다.
   * 서브카테고리는 카테고리에만 달려 있고 이름과는 아무 관계가 없다. 이름이 20자면
   * 요청 20번이고, 게이트웨이 레이트리미터(burst 40 · 초당 20)에 걸려 429 가 났다.
   * 그 429 는 어디서도 처리되지 않아 목록이 조용히 비었다.
   */
  it("이름을 타이핑해도 서브카테고리를 다시 부르지 않는다", async () => {
    render(withI18n(<AdminProduct />));

    await userEvent.click(screen.getByText("select-category"));
    await waitFor(() => expect(subcategoryCalls()).toHaveLength(1));

    await userEvent.type(screen.getByLabelText("name"), "테스트 상품 이름");

    // 카테고리를 고른 그 한 번이 전부여야 한다.
    expect(subcategoryCalls()).toHaveLength(1);
  });

  it("카테고리를 고르면 그때 한 번 부른다", async () => {
    render(withI18n(<AdminProduct />));

    expect(subcategoryCalls()).toHaveLength(0);

    await userEvent.click(screen.getByText("select-category"));

    await waitFor(() => expect(subcategoryCalls()).toHaveLength(1));
    expect(subcategoryCalls()[0][1]).toEqual({ params: { category: "cat-1" } });
  });

  /**
   * 예전에는 `loadCategories` 가 서브카테고리 effect 안에 있었다 — category 와 name 이
   * 바뀔 때마다 카테고리 <b>전체</b>를 다시 받았다.
   */
  it("카테고리 전체 목록은 마운트 때 한 번만 받는다", async () => {
    render(withI18n(<AdminProduct />));

    await userEvent.click(screen.getByText("select-category"));
    await userEvent.type(screen.getByLabelText("name"), "abcde");

    expect(mockLoadCategories).toHaveBeenCalledTimes(1);
  });

  /**
   * 🐛 세 effect 의 async 함수에 catch 가 하나도 없었다. 실패는 콘솔의 "처리되지 않은
   * 거절"로만 남고 화면은 그대로였다 — 관리자는 목록이 빈 것이 장애인지 데이터가 없는
   * 건지 알 수 없었다.
   */
  it("불러오기가 실패하면 화면에 알린다", async () => {
    mockApiGet.mockRejectedValue({ response: { data: { message: "서버 오류" } } });

    render(withI18n(<AdminProduct />));

    expect(await screen.findByText("서버 오류")).toBeInTheDocument();
  });

  it("429 는 레이트리밋이라고 말해 준다 — 원인이 다르면 대응도 다르다", async () => {
    mockApiGet.mockRejectedValue({ response: { status: 429 } });

    render(withI18n(<AdminProduct />));

    expect(await screen.findByText(/요청이 너무 잦습니다/)).toBeInTheDocument();
  });

  it("카테고리를 비우면 서브카테고리 목록도 비운다", async () => {
    // 예전에는 category 가 비어도 이전 카테고리의 서브카테고리가 그대로 남아 있었다.
    mockGetRequest.mockResolvedValue({ data: [{ id: "s1" }] });

    render(withI18n(<AdminProduct />));
    await userEvent.click(screen.getByText("select-category"));

    await waitFor(() => expect(subcategoryCalls()).toHaveLength(1));
  });
});
