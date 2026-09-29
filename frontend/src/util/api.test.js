import api, { getOrders, getOrder, loadCart, loadCheckOut, getCoupons, processPayment } from "util/api";

/**
 * 데이터 계층의 두 가지 계약을 고정한다.
 *
 * 1. **오류를 삼키지 않는다.** 예전에는 15곳이 `catch { console.log(...) }` 로 끝나 함수가
 *    `undefined` 를 반환했다. 호출부는 그걸 성공으로 받아 `.map()` 하다가 죽었고, 진짜
 *    원인(401·500·타임아웃)은 콘솔에만 남았다. react-query 의 throwOnError 도 무력화됐다.
 * 2. **신원을 쿼리로 보내지 않는다.** 서버가 게이트웨이의 검증된 헤더에서 신원을 얻도록
 *    바뀌었으므로, 클라이언트가 email/userId 를 보내면 "이 값이 신원을 정한다"는 오해만 남는다.
 */
describe("api data layer", () => {
  let getSpy;
  let postSpy;

  beforeEach(() => {
    getSpy = jest.spyOn(api, "get");
    postSpy = jest.spyOn(api, "post");
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe("propagates failures instead of swallowing them", () => {
    // 각 함수가 실패를 그대로 올려보내야 한다. 하나라도 삼키면 호출부가 엉뚱한 곳에서 죽는다.
    const cases = [
      ["getOrders", () => getOrders("all")],
      ["getOrder", () => getOrder(1)],
      ["loadCart", () => loadCart()],
      ["loadCheckOut", () => loadCheckOut()],
      ["getCoupons", () => getCoupons()],
    ];

    it.each(cases)("%s rejects when the request fails", async (_name, call) => {
      const failure = Object.assign(new Error("Request failed"), {
        response: { status: 500, data: { message: "boom" } },
      });
      getSpy.mockRejectedValue(failure);

      await expect(call()).rejects.toThrow("Request failed");
    });

    it("does not mask the cause when the error body is empty", async () => {
      // 예전 catch 는 `error.response?.data.message` 를 읽어, 본문 없는 500 에서
      // catch 안에서 다시 TypeError 를 냈고 원래 오류가 가려졌다.
      const failure = Object.assign(new Error("Network Error"), { response: { status: 500 } });
      getSpy.mockRejectedValue(failure);

      await expect(getOrders("all")).rejects.toThrow("Network Error");
    });
  });

  describe("does not send identity as a parameter", () => {
    beforeEach(() => {
      getSpy.mockResolvedValue({ data: [] });
    });

    it("getOrders sends only the filter", async () => {
      await getOrders("paid");

      const [, config] = getSpy.mock.calls[0];
      expect(config.params).toEqual({ filter: "paid" });
      expect(config.params).not.toHaveProperty("email");
    });

    it("getOrder sends no params at all", async () => {
      await getOrder(7);

      const [url, config] = getSpy.mock.calls[0];
      expect(url).toContain("/order-service/api/order/7");
      expect(config?.params).toBeUndefined();
    });

    it.each([
      ["loadCart", () => loadCart()],
      ["loadCheckOut", () => loadCheckOut()],
      ["getCoupons", () => getCoupons()],
    ])("%s sends no identity params", async (_name, call) => {
      await call();

      const [, config] = getSpy.mock.calls[0];
      expect(config?.params).toBeUndefined();
    });
  });

  describe("processPayment", () => {
    it("is a POST, and actually carries the request body", async () => {
      // 예전에는 GET 이었고 요청 본문을 GET 의 config 자리에 넘겨서 서버에는 아무것도
      // 전달되지 않았다. 상태를 바꾸는 호출이므로 프록시가 재시도·캐시해서도 안 된다.
      postSpy.mockResolvedValue({ data: { ok: true } });
      const payload = { orderId: 1, amount: 100 };

      await processPayment(payload);

      expect(postSpy).toHaveBeenCalledWith(
        "/order-service/api/order/payment/process",
        payload,
        expect.anything()
      );
    });
  });
});
