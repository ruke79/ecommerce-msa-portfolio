import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import Summary from "components/checkout/Summary";
import { postRequest } from "util/api";
import { useFetchCoupons } from "hook/hooks";
import { withI18n } from "i18n/testing";

jest.mock("util/api");
jest.mock("hook/hooks");

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  ...jest.requireActual("react-router-dom"),
  useNavigate: () => mockNavigate,
}));

const cart = {
  cartTotal: 103000,
  products: [{ id: "1", productId: "10", name: "티셔츠", qty: 1, price: 100000, shipping: 3000 }],
};

const renderSummary = (over = {}, lang = "ko") =>
  render(
    withI18n(
      <Summary
        selectedAddress={{ id: "1", firstname: "홍" }}
        user={{ email: "buyer@example.com" }}
        cart={cart}
        paymentMethod="paypal"
        totalAfterDiscount=""
        setTotalAfterDiscount={jest.fn()}
        setLoading={jest.fn()}
        {...over}
      />,
      lang
    )
  );

/**
 * 🔒 주문 생성 요청에 <b>금액이 들어가면 안 된다</b> (docs/40 #10).
 *
 * <p>예전에는 여기서 `total` 과 `totalBeforeDiscount` 를 보냈고 서버는 그것을 그대로
 * 저장했다. PayPal 검증은 "결제액 == DB 의 주문 총액" 만 보므로, DevTools 로 total 을 1로
 * 바꾸면 1달러 결제가 <b>정상적으로 검증을 통과했다.</b>
 *
 * <p>서버 쪽에서도 필드를 지웠으니 보내도 버려지지만, 화면이 계속 보내고 있으면 다음
 * 사람이 "여기 total 을 보내네" 하고 서버에 필드를 되살린다. 양쪽을 다 막는다.
 */
describe("주문 요약", () => {
  beforeEach(() => {
    useFetchCoupons.mockReturnValue({ coupons: [], isSuccess: true, isPending: false });
    postRequest.mockResolvedValue({ data: { orderId: "77" } });
  });

  afterEach(() => jest.clearAllMocks());

  const placeOrder = async () => {
    await userEvent.click(screen.getByRole("button", { name: /주문하기/ }));
    await waitFor(() => expect(postRequest).toHaveBeenCalled());
    return postRequest.mock.calls[0][1];
  };

  it("주문 생성 요청에 금액을 담지 않는다 — 서버가 계산한다", async () => {
    renderSummary();

    const body = await placeOrder();

    expect(body).not.toHaveProperty("total");
    expect(body).not.toHaveProperty("totalBeforeDiscount");
    expect(body).not.toHaveProperty("totalAfterDiscount");
  });

  /** 쿠폰을 적용해 화면에 할인 금액이 떠 있어도 그 숫자를 보내지 않는다. */
  it("쿠폰이 적용돼 있어도 할인 금액을 보내지 않는다", async () => {
    renderSummary({ totalAfterDiscount: 90000 });

    const body = await placeOrder();

    expect(body).not.toHaveProperty("total");
    expect(Object.values(body)).not.toContain(90000);
  });

  it("쿠폰 이름과 배송지·결제수단은 보낸다 — 서버가 계산하려면 필요하다", async () => {
    renderSummary();

    const body = await placeOrder();

    expect(body).toMatchObject({ paymentMethod: "paypal" });
    expect(body).toHaveProperty("shippingAddress");
    expect(body).toHaveProperty("couponApplied");
  });

  // 🔒 주문자는 게이트웨이가 검증한 토큰의 uid 다 — 본문의 신원은 서버가 읽지 않고(필드가 없다), 보내면 "이 값이
  // 신원을 정한다" 는 오해만 남긴다(docs/48 §6-⑥).
  it("신원(email)은 보내지 않는다", async () => {
    renderSummary();

    const body = await placeOrder();

    expect(body).not.toHaveProperty("email");
    expect(Object.values(body)).not.toContain("buyer@example.com");
  });

  it("주문이 만들어지면 그 주문 화면으로 간다", async () => {
    renderSummary();

    await placeOrder();

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/order/77"));
  });

  /**
   * 서버가 쿠폰을 확인하지 못하면 주문을 거절한다. 그 사유가 화면에 보여야 사용자가
   * 쿠폰을 빼고 다시 시도할 수 있다.
   */
  it("서버가 거절하면 그 이유를 보여준다", async () => {
    postRequest.mockRejectedValue({
      response: { data: { message: "사용 기간이 지난 쿠폰입니다." } },
    });

    renderSummary();
    await userEvent.click(screen.getByRole("button", { name: /주문하기/ }));

    expect(await screen.findByText(/사용 기간이 지난 쿠폰입니다/)).toBeInTheDocument();
  });

  it("결제수단을 고르지 않으면 주문하지 않는다", async () => {
    renderSummary({ paymentMethod: "" });

    await userEvent.click(screen.getByRole("button", { name: /주문하기/ }));

    expect(postRequest).not.toHaveBeenCalled();
  });

  it("배송지를 고르지 않으면 주문하지 않는다", async () => {
    renderSummary({ selectedAddress: null });

    await userEvent.click(screen.getByRole("button", { name: /주문하기/ }));

    expect(postRequest).not.toHaveBeenCalled();
  });

  /**
   * 🔴 예전에는 "Total" 한 줄이 전부였고 그 안에 배송비가 녹아 있었다. 사용자는 자기가
   * 배송비를 얼마 내는지 <b>주문하는 순간까지 알 수 없었다.</b>
   */
  it("상품 금액과 배송비를 나눠 보여준다", async () => {
    renderSummary();

    // 상점 통화(테스트에서는 USD)를 Intl 이 그린다 — 한국어 로캘은 "US$". 예전에는 "100000$" 였다.
    expect(await screen.findByText("상품 금액")).toBeInTheDocument();
    expect(screen.getByText("US$100,000.00")).toBeInTheDocument();
    expect(screen.getByText("배송비")).toBeInTheDocument();
    expect(screen.getByText("US$3,000.00")).toBeInTheDocument();
    expect(screen.getByText("US$103,000.00")).toBeInTheDocument();
  });

  it("배송비 정보가 없는 옛 장바구니에도 세 줄이 뜬다", async () => {
    renderSummary({ cart: { cartTotal: 50000, products: [{ id: "1", qty: 1, price: 50000 }] } });

    expect(await screen.findByText("배송비")).toBeInTheDocument();
    expect(screen.getByText("US$0.00")).toBeInTheDocument();
  });
});

/**
 * 🔴 문구를 사전으로 옮긴 것이 실제로 화면에 닿는지 확인한다.
 *
 * <p>한국어 테스트는 사전을 거치든 하드코딩이든 똑같이 통과한다 — 일본어로 그려 봐야
 * 컴포넌트가 정말 {@code t()} 를 쓰고 있는지 드러난다.
 */
describe("주문 요약 (일본어)", () => {
    beforeEach(() => {
        useFetchCoupons.mockReturnValue({ coupons: [], isSuccess: true, isPending: false });
        postRequest.mockResolvedValue({ data: { orderId: "77" } });
    });

    afterEach(() => jest.clearAllMocks());

    it("🔴 금액 항목이 일본어로 뜬다", async () => {
        renderSummary({}, "ja");

        expect(await screen.findByText("商品の小計")).toBeInTheDocument();
        expect(screen.getByText("送料")).toBeInTheDocument();
        expect(screen.getByText("合計")).toBeInTheDocument();
    });

    it("주문 버튼도 일본어다 — 한국어가 남아 있으면 안 된다", () => {
        renderSummary({}, "ja");

        expect(screen.getByRole("button", { name: /注文を確定する/ })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: /주문하기/ })).not.toBeInTheDocument();
    });

    /** 금액은 같고 표기만 언어를 따른다 — 일본어 로캘의 달러는 "$". */
    it("금액은 언어와 무관하게 같고, 통화 기호만 로캘을 따른다", async () => {
        renderSummary({}, "ja");

        expect(await screen.findByText("$100,000.00")).toBeInTheDocument();
        expect(screen.getByText("$3,000.00")).toBeInTheDocument();
        expect(screen.getByText("$103,000.00")).toBeInTheDocument();
    });
});
