import { render as rtlRender, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import CouponPicker from "components/checkout/CouponPicker";
import { withI18n } from "i18n/testing";

// 컴포넌트가 useTranslation 을 쓰므로 Provider 로 감싼다 — rerender 도 같은 wrapper 를 쓴다.
const render = (ui, options) => rtlRender(ui, { wrapper: ({ children }) => withI18n(children), ...options });

const coupon = (over = {}) => ({
  id: "1",
  name: "웰컴 10% 할인 쿠폰",
  discount: 10,
  scope: "ALL",
  targets: [],
  usable: true,
  endDate: "2026-12-31T23:59:00",
  ...over,
});

/**
 * 🐛 회귀 대상: **쿠폰을 고를 수 없었다.**
 *
 * 예전 UI 는 자유 입력 칸 하나였는데, 이 서비스에는 "코드를 입력해 쿠폰을 받는다"는 개념이
 * 없다 — 쿠폰은 사용자별 행으로 발급되고 적용은 그 행의 **이름**으로 찾는다. 즉 통하는 값은
 * 이미 가진 쿠폰의 이름을 글자 그대로 맞춘 것뿐이었고, 사용자는 그 이름을 알 방법이 없었다.
 * 그래서 화면이 목록의 첫 번째를 자동으로 적용하고 있었다.
 */
describe("CouponPicker — 가진 것을 보여주고 고르게 한다", () => {
  it("보유 쿠폰을 모두 보여준다", () => {
    render(
      <CouponPicker
        coupons={[coupon(), coupon({ id: "2", name: "가을 세일", discount: 20 })]}
        selected=""
        onSelect={jest.fn()}
      />
    );

    expect(screen.getByText(/보유 쿠폰 2장/)).toBeInTheDocument();
    expect(screen.getByText("웰컴 10% 할인 쿠폰")).toBeInTheDocument();
    expect(screen.getByText("가을 세일")).toBeInTheDocument();
  });

  it("고르면 그 쿠폰 이름을 넘긴다", async () => {
    const onSelect = jest.fn();
    render(
      <CouponPicker coupons={[coupon()]} selected="" onSelect={onSelect} />
    );

    await userEvent.click(screen.getByRole("radio", { name: /웰컴 10%/ }));

    expect(onSelect).toHaveBeenCalledWith("웰컴 10% 할인 쿠폰");
  });

  /**
   * 예전에는 목록을 받자마자 coupons[0] 을 적용했다. 제약 있는 쿠폰이 생긴 지금은
   * "장바구니에 맞지 않는 쿠폰이 저절로 적용되고 할인 0원"이 가능하다.
   */
  it("아무것도 미리 고르지 않는다 — 자동 적용하지 않는다", () => {
    const onSelect = jest.fn();
    render(
      <CouponPicker coupons={[coupon(), coupon({ id: "2", name: "가을 세일" })]}
        selected="" onSelect={onSelect} />
    );

    expect(onSelect).not.toHaveBeenCalled();
    // 선택된 것은 "사용 안 함" 하나뿐이다.
    expect(screen.getByRole("radio", { name: /쿠폰 사용 안 함/ })).toBeChecked();
  });

  it("고른 쿠폰을 되돌릴 수 있다", async () => {
    const onSelect = jest.fn();
    render(
      <CouponPicker coupons={[coupon()]} selected="웰컴 10% 할인 쿠폰" onSelect={onSelect} />
    );

    await userEvent.click(screen.getByRole("radio", { name: /쿠폰 사용 안 함/ }));

    expect(onSelect).toHaveBeenCalledWith("");
  });

  /**
   * 목록에서 빼 버리면 "쿠폰이 있었는데 사라졌다"가 되고, 만료됐다는 것을 알 방법이 없다.
   */
  it("만료된 쿠폰은 감추지 않고 고를 수 없게만 한다", () => {
    render(
      <CouponPicker
        coupons={[coupon({ usable: false })]}
        selected=""
        onSelect={jest.fn()}
      />
    );

    expect(screen.getByText("웰컴 10% 할인 쿠폰")).toBeInTheDocument();
    expect(screen.getByText(/사용 기간이 지났습니다/)).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /웰컴 10%/ })).toBeDisabled();
  });

  it("제약 있는 쿠폰은 범위를 배지로 알린다", () => {
    render(
      <CouponPicker
        coupons={[
          coupon({ scope: "CATEGORY", targets: ["c1", "c2"] }),
          coupon({ id: "2", name: "상품 한정", scope: "PRODUCT", targets: ["p1"] }),
          coupon({ id: "3", name: "전체 할인", scope: "ALL", targets: [] }),
        ]}
        selected=""
        onSelect={jest.fn()}
      />
    );

    expect(screen.getByText("일부 카테고리 2개")).toBeInTheDocument();
    expect(screen.getByText("일부 상품 1개")).toBeInTheDocument();
    // 제약 없는 쿠폰에는 배지를 붙이지 않는다 — 없는 제약을 있는 것처럼 보이면 안 된다.
    // 쿠폰은 3장인데 배지는 2개다.
    expect(screen.queryAllByText(/일부/)).toHaveLength(2);
  });

  it("쿠폰이 없으면 없다고 말한다", () => {
    render(<CouponPicker coupons={[]} selected="" onSelect={jest.fn()} />);

    expect(screen.getByText(/사용할 수 있는 쿠폰이 없습니다/)).toBeInTheDocument();
  });

  it("목록이 null 이어도 터지지 않는다", () => {
    // 서버가 예전에 쿠폰이 없을 때 null 을 돌려줬다(지금은 빈 배열).
    expect(() =>
      render(<CouponPicker coupons={null} selected="" onSelect={jest.fn()} />)
    ).not.toThrow();
  });

  it("적용 중에는 다시 고를 수 없다 — 중복 요청 방지", () => {
    render(
      <CouponPicker coupons={[coupon()]} selected="" onSelect={jest.fn()} disabled />
    );

    expect(screen.getByRole("radio", { name: /웰컴 10%/ })).toBeDisabled();
  });
});
