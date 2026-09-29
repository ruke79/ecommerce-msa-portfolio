import { useState } from "react";
import { render as rtlRender, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import AddShipping from "components/checkout/AddShipping";
import { saveShippingAddress, updateShippingAddress } from "util/api";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

jest.mock("util/api", () => ({
  __esModule: true,
  saveShippingAddress: jest.fn(),
  updateShippingAddress: jest.fn(),
}));

const initialValue = {
  firstname: "", lastname: "", phoneNumber: "", state: "", city: "",
  zipCode: "", address1: "", address2: "", country: "",
};

/**
 * 부모가 shipping 상태를 들고 있는 실제 사용 형태를 그대로 재현한다. 상태를 고정값으로
 * 넣으면 이 버그(부모 상태 갱신 → 폼 초기화 경합)가 재현되지 않는다.
 */
function Harness({ invalidate = jest.fn(), editing = null, onCancelEdit = jest.fn() }) {
  const [shipping, setShipping] = useState(initialValue);
  return (
    <AddShipping
      shipping={shipping}
      setShipping={setShipping}
      initialValue={initialValue}
      invalidate={invalidate}
      setLoading={jest.fn()}
      editing={editing}
      onCancelEdit={onCancelEdit}
    />
  );
}

/** 나라를 잘못 골라 KP 로 저장된 실제 주소 — 이걸 고치려고 수정 기능이 생겼다. */
const saved = {
  id: "889000000000000001", active: true,
  firstname: "Sangyong", lastname: "Yun", phoneNumber: "01032123123",
  state: "Jeollanam-do", city: "Mokpo-si", zipCode: "55555",
  address1: "samhak-do 702ho", address2: "",
  country: "Korea, Democratic People'S Republic of",
};

describe("AddShipping", () => {
  afterEach(() => jest.clearAllMocks());

  // ShippingInput 은 떠 있는 라벨(span)을 쓰므로 input 에는 placeholder 속성이 없다.
  // 접근 가능한 이름(aria-label)으로 찾는다 — 스크린리더가 보는 것과 같은 경로다.

  // 국가 선택은 MUI Select 라 네이티브 <select> 가 아니다. 목록을 열고 항목을 눌러야 한다.
  const selectCountry = async (name) => {
    await userEvent.click(screen.getByRole("combobox"));
    await userEvent.click(await screen.findByRole("option", { name }));
  };

  /**
   * 🐛 배송 주소 폼에도 SignInPage·RegisterPage 와 같은 이중 상태 버그가 있었다.
   *
   * 여기서는 결과가 더 나쁘다 — 로그인은 실패하면 사용자가 알아채고 다시 시도하지만,
   * **주소는 잘못 저장돼도 그럴듯해 보인다.** 그대로 결제까지 진행되면 물건이 엉뚱한 곳으로
   * 간다. 배송 주소는 delivery-service 가 스냅샷으로 복사해 두는 값이라, 잘못된 값이
   * 주문·배송·분쟁 기록에 그대로 남는다.
   */
  // ── 수정 ────────────────────────────────────────────────────────────────

  it("수정 모드면 저장된 값이 채워져 있고 버튼이 Update 다", () => {
    render(<Harness editing={saved} />);

    expect(screen.getByRole("status")).toHaveTextContent("배송지를 수정하고 있습니다");
    expect(screen.getByLabelText("*주소")).toHaveValue("samhak-do 702ho");
    expect(screen.getByLabelText("*우편번호")).toHaveValue("55555");
    expect(screen.getByRole("button", { name: "배송지 수정" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "취소" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "배송지 저장" })).not.toBeInTheDocument();
  });

  /** 🔴 핵심 — 나라만 바꿔 저장하면 그 주소의 id 로 PUT 이 나가고, 새 주소를 만들지 않는다. */
  it("🔴 수정하면 그 주소의 id 로 update 를 부르고 save 는 부르지 않는다", async () => {
    updateShippingAddress.mockResolvedValue({});
    const invalidate = jest.fn();
    const onCancelEdit = jest.fn();
    render(<Harness editing={saved} invalidate={invalidate} onCancelEdit={onCancelEdit} />);

    await selectCountry("대한민국");
    await userEvent.click(screen.getByRole("button", { name: "배송지 수정" }));

    await waitFor(() => expect(updateShippingAddress).toHaveBeenCalledTimes(1));
    const [id, values] = updateShippingAddress.mock.calls[0];
    expect(id).toBe("889000000000000001");                 // 18자리 그대로 — 문자열
    expect(values).toEqual(expect.objectContaining({
      country: "KR",
      address1: "samhak-do 702ho",
      zipCode: "55555",
    }));
    // 폼에 없는 값은 싣지 않는다 — active 를 실어 보내면 기본 배송지가 슬쩍 바뀔 수 있다.
    expect(values).not.toHaveProperty("active");
    expect(values).not.toHaveProperty("id");
    expect(saveShippingAddress).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalled();
    expect(onCancelEdit).toHaveBeenCalled();
  });

  /**
   * 🔴 옛 주소는 나라를 영어 이름으로 저장했다. 선택지의 값은 이제 코드라, 그대로 넣으면 칸이 비어 보이고
   * 손대지 않고 저장하면 빈 나라로 검증에 걸린다 — 폼에 넣을 때 코드로 바꾼다(docs/60 §11).
   */
  it("🔴 옛 주소(영어 이름)를 고치면 나라 칸에 지금 언어 이름이 보이고, 코드로 저장된다", async () => {
    updateShippingAddress.mockResolvedValue({});
    render(<Harness editing={saved} />);

    expect(screen.getByRole("combobox")).toHaveTextContent("북한");
    await userEvent.click(screen.getByRole("button", { name: "배송지 수정" }));

    await waitFor(() => expect(updateShippingAddress).toHaveBeenCalledTimes(1));
    expect(updateShippingAddress.mock.calls[0][1]).toEqual(expect.objectContaining({ country: "KP" }));
  });

  it("일본어 화면이면 나라 목록이 일본어다 — 값은 같은 코드", async () => {
    saveShippingAddress.mockResolvedValue({});
    rtlRender(withI18n(<Harness editing={{ ...saved, country: "KR" }} />, "ja"));

    expect(screen.getByRole("combobox")).toHaveTextContent("韓国");
    await userEvent.click(screen.getByRole("combobox"));
    expect(await screen.findByRole("option", { name: "日本" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Japan" })).not.toBeInTheDocument();
  });

  it("Cancel 을 누르면 수정을 접고 아무것도 보내지 않는다", async () => {
    const onCancelEdit = jest.fn();
    render(<Harness editing={saved} onCancelEdit={onCancelEdit} />);

    await userEvent.click(screen.getByRole("button", { name: "취소" }));

    expect(onCancelEdit).toHaveBeenCalledTimes(1);
    expect(updateShippingAddress).not.toHaveBeenCalled();
    expect(saveShippingAddress).not.toHaveBeenCalled();
  });

  it("🐛 keeps every character typed into the address fields", async () => {
    render(<Harness />);

    const address1 = screen.getByLabelText("*주소");
    await userEvent.type(address1, "서울특별시 강남구 테헤란로 123");

    expect(address1).toHaveValue("서울특별시 강남구 테헤란로 123");
  });

  it("keeps every character in the name and phone fields", async () => {
    render(<Harness />);

    const first = screen.getByLabelText("*성");
    const phone = screen.getByLabelText("*전화번호");

    await userEvent.type(first, "길동");
    await userEvent.type(phone, "01012345678");

    expect(first).toHaveValue("길동");
    expect(phone).toHaveValue("01012345678");
  });

  it("submits exactly what was typed", async () => {
    saveShippingAddress.mockResolvedValue({});

    render(<Harness />);

    await userEvent.type(screen.getByLabelText("*성"), "길동");
    await userEvent.type(screen.getByLabelText("*이름"), "홍");
    await userEvent.type(screen.getByLabelText("*시/도"), "서울");
    await userEvent.type(screen.getByLabelText("*시/군/구"), "강남구");
    await userEvent.type(screen.getByLabelText("*우편번호"), "06234");
    await userEvent.type(screen.getByLabelText("*전화번호"), "01012345678");
    await userEvent.type(screen.getByLabelText("*주소"), "테헤란로 123");
    await userEvent.type(screen.getByLabelText("*상세 주소"), "5층 501호");
    await selectCountry("대한민국");

    await userEvent.click(screen.getByRole("button", { name: "배송지 저장" }));

    await waitFor(() => expect(saveShippingAddress).toHaveBeenCalled());

    // 저장된 주소가 입력과 한 글자도 다르면 안 된다.
    expect(saveShippingAddress).toHaveBeenCalledWith(
      expect.objectContaining({
        firstname: "길동",
        lastname: "홍",
        state: "서울",
        city: "강남구",
        zipCode: "06234",
        phoneNumber: "01012345678",
        address1: "테헤란로 123",
        address2: "5층 501호",
        country: "KR",
      })
    );
  });

  it("🐛 accepts ordinary Korean names", async () => {
    // 이름 최소 길이가 3이었다. 한국 이름은 성 1글자("홍") + 이름 2글자("길동")가 가장
    // 흔하므로 **정상적인 한국 이름이 거의 다 거부됐다.** 배송지를 저장할 수 없다는 뜻이다.
    render(<Harness />);

    await userEvent.type(screen.getByLabelText("*성"), "길동");
    await userEvent.type(screen.getByLabelText("*이름"), "홍");
    // 다른 필드를 건드려 검증을 트리거한다.
    await userEvent.click(screen.getByRole("button", { name: "배송지 저장" }));

    expect(screen.queryByText(/성을 입력해 주세요/)).not.toBeInTheDocument();
    expect(screen.queryByText(/이름을 입력해 주세요/)).not.toBeInTheDocument();
  });

  it("names the field it is complaining about", async () => {
    // 전화번호 검증 메시지가 전부 "First Name ..." 으로 복사돼 있었다.
    render(<Harness />);

    await userEvent.click(screen.getByRole("button", { name: "배송지 저장" }));

    expect(await screen.findByText(/전화번호를 입력해 주세요/)).toBeInTheDocument();
  });

  it("does not save when a required field is missing", async () => {
    saveShippingAddress.mockResolvedValue({});

    render(<Harness />);
    await userEvent.type(screen.getByLabelText("*성"), "길동");
    await userEvent.click(screen.getByRole("button", { name: "배송지 저장" }));

    // 주소 없이 저장되면 배송이 불가능한 주문이 생긴다.
    await waitFor(() => expect(screen.getAllByText(/입력해 주세요/).length).toBeGreaterThan(0));
    expect(saveShippingAddress).not.toHaveBeenCalled();
  });

  it("refreshes the address list after a successful save", async () => {
    const invalidate = jest.fn();
    saveShippingAddress.mockResolvedValue({});

    render(<Harness invalidate={invalidate} />);

    await userEvent.type(screen.getByLabelText("*성"), "길동");
    await userEvent.type(screen.getByLabelText("*이름"), "홍");
    await userEvent.type(screen.getByLabelText("*시/도"), "서울");
    await userEvent.type(screen.getByLabelText("*시/군/구"), "강남구");
    await userEvent.type(screen.getByLabelText("*우편번호"), "06234");
    await userEvent.type(screen.getByLabelText("*전화번호"), "01012345678");
    await userEvent.type(screen.getByLabelText("*주소"), "테헤란로 123");
    await selectCountry("대한민국");

    await userEvent.click(screen.getByRole("button", { name: "배송지 저장" }));

    await waitFor(() => expect(invalidate).toHaveBeenCalled());
  });
});
