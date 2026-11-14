import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import CreateCategory from "components/admin/CreateCategory";
import { getRequest, postRequest } from "util/api";
import { withI18n } from "i18n/testing";

jest.mock("util/api", () => ({
  __esModule: true,
  getRequest: jest.fn(),
  postRequest: jest.fn(),
}));

jest.mock("react-hot-toast", () => ({
  __esModule: true,
  default: { error: jest.fn(), success: jest.fn() },
}));

// 🐛 2026-11-14: AdminInput 이 호출부의 onChange 를 부르지 않아, 이 화면들은 사람이 **타이핑한 값을 보내지 못했다**
// (제출은 그 onChange 가 채우는 화면 상태를 읽는다 — 카테고리는 slugify(undefined) 로 늘 "추가 실패").
// 그래서 props 로 값을 미리 넣지 않고, 실제로 입력란에 타이핑한 뒤 나가는 요청을 본다.
// [JA] 入力欄に実際にタイプしてから、出ていくリクエストを見る(以前は画面の状態が空のまま送られた)。

beforeEach(() => {
  jest.clearAllMocks();
  getRequest.mockResolvedValue({ data: [{ id: 7, name: "Fashion" }] });
  postRequest.mockResolvedValue({ response: {} });
});

test("🔴 카테고리 이름을 타이핑하고 추가하면 그 이름과 slug 가 나간다", async () => {
  render(withI18n(<CreateCategory />));
  const [categoryInput] = screen.getAllByRole("textbox");
  await userEvent.type(categoryInput, "Shoes");
  const [addCategory] = screen.getAllByRole("button");
  await userEvent.click(addCategory);

  await waitFor(() =>
    expect(postRequest).toHaveBeenCalledWith("/catalog-service/api/category/category", { name: "Shoes", slug: "Shoes" })
  );
});

test("🔴 하위 카테고리(관리자 화면의 두 번째 폼) — 타이핑한 이름 · 고른 상위로 나간다(상위를 고른 뒤에도 이름이 남는다)", async () => {
  render(withI18n(<CreateCategory />));
  const inputs = screen.getAllByRole("textbox");
  await userEvent.type(inputs[1], "Sneakers");
  // MUI select — 열고 고른다. 고르면 화면 상태 parent 가 바뀌어 Formik 이 다시 초기화된다(enableReinitialize) —
  // 예전에는 그때 이름이 화면 상태(비어 있음)로 되돌아가 타이핑한 글자가 사라졌다.
  await userEvent.click(screen.getByRole("combobox"));
  await userEvent.click(await screen.findByRole("option", { name: "Fashion" }));
  expect(screen.getAllByRole("textbox")[1]).toHaveValue("Sneakers");
  await waitFor(() => expect(screen.getByRole("combobox")).toHaveTextContent("Fashion"));
  const addButtons = Array.from(document.querySelectorAll("form button"));
  await userEvent.click(addButtons[addButtons.length - 1]);

  await waitFor(() =>
    expect(postRequest).toHaveBeenCalledWith("/catalog-service/api/category/subcategory",
      { subcategoryName: "Sneakers", parent: 7, slug: "Sneakers" })
  );
});
