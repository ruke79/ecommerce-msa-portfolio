import { render as rtlRender, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import ListShipping from "components/checkout/ListShipping";
import { deleteAddress, selectShippingAddress } from "util/api";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({
  __esModule: true,
  selectShippingAddress: jest.fn(),
  deleteAddress: jest.fn(),
}));

const address = {
  id: "889000000000000001", active: false,
  firstname: "Sangyong", lastname: "Yun", phoneNumber: "01032123123",
  address1: "samhak-do 702ho", address2: "", city: "Mokpo-si", state: "Jeollanam-do",
  zipCode: "55555", country: "Korea, Democratic People'S Republic of",
};

/**
 * 배송지 카드의 버튼들. 지금까지 등록·삭제만 있었다 — 나라 하나를 잘못 골랐으면 지우고
 * 다시 넣어야 했고, 그러면 기본 배송지 표시도 함께 사라졌다.
 */
describe("ListShipping", () => {
  beforeEach(() => jest.clearAllMocks());

  const renderList = (onEdit = jest.fn()) => {
    render(<ListShipping addresses={[address]} invalidate={jest.fn()} user={{}} onEdit={onEdit} />);
    return onEdit;
  };

  it("🔴 수정 버튼을 누르면 그 배송지를 통째로 넘긴다 — 카드 클릭(기본 배송지 선택)은 일어나지 않는다", async () => {
    const onEdit = renderList();

    await userEvent.click(screen.getByRole("button", { name: "배송지 수정" }));

    expect(onEdit).toHaveBeenCalledWith(address);
    expect(selectShippingAddress).not.toHaveBeenCalled();
    expect(deleteAddress).not.toHaveBeenCalled();
  });

  it("나라는 화면 언어의 이름으로 — 옛 주소(영어 이름)·코드 모두", () => {
    rtlRender(withI18n(<ListShipping addresses={[address, { ...address, id: "2", country: "JP" }]}
      invalidate={jest.fn()} user={{}} onEdit={jest.fn()} />, "ja"));

    expect(screen.getByText(/北朝鮮/)).toBeInTheDocument();
    expect(screen.getByText(/日本/)).toBeInTheDocument();
    expect(screen.queryByText(/Korea/)).not.toBeInTheDocument();
  });

  it("삭제 버튼은 여전히 지운다", async () => {
    deleteAddress.mockResolvedValue({});
    renderList();

    await userEvent.click(screen.getByRole("button", { name: "배송지 삭제" }));

    expect(deleteAddress).toHaveBeenCalledWith("889000000000000001");
  });

  it("카드 자체를 누르면 기본 배송지로 고른다", async () => {
    selectShippingAddress.mockResolvedValue({});
    renderList();

    await userEvent.click(screen.getByText("samhak-do 702ho"));

    expect(selectShippingAddress).toHaveBeenCalledWith("889000000000000001");
  });
});
