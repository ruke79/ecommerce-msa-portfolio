import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import AllProduct from "components/admin/Product";
import { putRequest, patchRequest, deleteRequest } from "util/api";
import { withI18n } from "i18n/testing";

jest.mock("util/api", () => ({
  __esModule: true,
  putRequest: jest.fn(),
  patchRequest: jest.fn(),
  deleteRequest: jest.fn(),
}));

const product = (over = {}) => ({
  id: "1",
  name: "테스트 상품",
  brand: "브랜드",
  description: "설명",
  slug: "test",
  shipping: "0",
  status: "ACTIVE",
  category: { name: "fashion" },
  skus: [{ sku: "FAS-000001", images: ["http://img/1.jpg"] }],
  createdAt: "2026-08-01",
  ...over,
});

describe("관리자 상품 목록 액션", () => {
  beforeEach(() => jest.clearAllMocks());

  it("수정 저장이 PUT 으로 나가고 목록이 갱신된다", async () => {
    putRequest.mockResolvedValue({ data: product({ name: "고친 이름" }) });
    render(withI18n(<AllProduct products={[product()]} />));

    await userEvent.click(screen.getByTestId("edit-1"));
    const nameInput = screen.getByTestId("edit-field-name");
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "고친 이름");
    await userEvent.click(screen.getByTestId("edit-save"));

    await waitFor(() => expect(putRequest).toHaveBeenCalledWith(
      "/catalog-service/api/product/1",
      expect.objectContaining({ name: "고친 이름" })
    ));
    expect(await screen.findByText("고친 이름")).toBeInTheDocument();
  });

  it("품절 토글이 PATCH status=SOLD_OUT 으로 나가고 배지가 뜬다", async () => {
    patchRequest.mockResolvedValue({ data: product({ status: "SOLD_OUT" }) });
    render(withI18n(<AllProduct products={[product()]} />));

    await userEvent.click(screen.getByTestId("soldout-1"));

    await waitFor(() => expect(patchRequest).toHaveBeenCalledWith(
      "/catalog-service/api/product/1/status?status=SOLD_OUT", null));
    // 상태 반영은 비동기다 — 관찰 가능한 결과(배지·버튼 라벨)를 기다려서 단언한다.
    expect(await screen.findByTestId("badge-1")).toHaveTextContent("품절");
    await waitFor(() =>
      expect(screen.getByTestId("soldout-1")).toHaveTextContent("판매 재개"));
  });

  it("삭제는 confirm 을 거쳐 DELETE 로 나가고 목록에서 사라진다", async () => {
    deleteRequest.mockResolvedValue({});
    jest.spyOn(window, "confirm").mockReturnValue(true);
    render(withI18n(<AllProduct products={[product()]} />));

    await userEvent.click(screen.getByTestId("delete-1"));

    await waitFor(() => expect(deleteRequest).toHaveBeenCalledWith("/catalog-service/api/product/1"));
    await waitFor(() =>
      expect(screen.queryByText("테스트 상품")).not.toBeInTheDocument());
    // confirm 문구가 되돌릴 수 없음을 말한다 — 소프트 삭제지만 화면·서버 어느 쪽에서도
    // 복구 경로가 없다(V8).
    expect(window.confirm.mock.calls[0][0]).toMatch(/되돌릴 수 없습니다/);
  });

  it("일본어 화면에서는 버튼·확인 문구가 일본어다", async () => {
    jest.spyOn(window, "confirm").mockReturnValue(false);
    render(withI18n(<AllProduct products={[product()]} />, "ja"));

    expect(screen.getByTestId("edit-1")).toHaveTextContent("編集");
    expect(screen.getByTestId("soldout-1")).toHaveTextContent("品切れ");
    await userEvent.click(screen.getByTestId("delete-1"));
    const question = window.confirm.mock.calls[0][0];
    expect(question).toContain("「테스트 상품」を削除します");
    // 상품 이름(서버 데이터)을 빼면 한글이 하나도 없어야 한다.
    expect(question.replace("테스트 상품", "")).not.toMatch(/[가-힣]/);
  });

  it("confirm 을 취소하면 아무 요청도 나가지 않는다", async () => {
    jest.spyOn(window, "confirm").mockReturnValue(false);
    render(withI18n(<AllProduct products={[product()]} />));

    await userEvent.click(screen.getByTestId("delete-1"));

    expect(deleteRequest).not.toHaveBeenCalled();
    expect(screen.getByText("테스트 상품")).toBeInTheDocument();
  });
});
