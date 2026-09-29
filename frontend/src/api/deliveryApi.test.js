import api from "util/api";
import { changeDeliveryAddress, cancelDelivery, fetchDeliveryByOrder } from "api/deliveryApi";

/**
 * 배송 API 는 <b>409 를 오류가 아니라 상태로</b> 다룬다.
 *
 * 409 는 "요청이 잘못됐다"가 아니라 "지금은 할 수 없다 — 이미 발송됐다"는 뜻이다. 400 과
 * 구분하지 못하면 사용자에게 "잘못 입력하셨습니다"를 띄우게 되는데, 사용자는 아무것도 잘못
 * 입력하지 않았다.
 */
describe("deliveryApi", () => {
  afterEach(() => jest.restoreAllMocks());

  describe("changeDeliveryAddress", () => {
    it("reports success with the server payload", async () => {
      jest.spyOn(api, "patch").mockResolvedValue({
        data: { changed: true, previousAddress: "A", newAddress: "B" },
      });

      await expect(changeDeliveryAddress(1, { addressLine1: "B" })).resolves.toEqual({
        ok: true,
        changed: true,
        previousAddress: "A",
        newAddress: "B",
      });
    });

    it("flags 409 as alreadyDispatched rather than a generic failure", async () => {
      jest.spyOn(api, "patch").mockRejectedValue({
        response: { status: 409, data: { message: "이미 발송되어 배송지를 변경할 수 없습니다" } },
      });

      const result = await changeDeliveryAddress(1, {});

      expect(result.ok).toBe(false);
      expect(result.alreadyDispatched).toBe(true);
      expect(result.message).toBe("이미 발송되어 배송지를 변경할 수 없습니다");
    });

    it("does not claim alreadyDispatched for a 400", async () => {
      jest.spyOn(api, "patch").mockRejectedValue({
        response: { status: 400, data: { message: "주소가 올바르지 않습니다" } },
      });

      const result = await changeDeliveryAddress(1, {});

      expect(result.ok).toBe(false);
      expect(result.alreadyDispatched).toBe(false);
    });

    it("falls back to a readable message when the server sends no body", async () => {
      // 서버가 본문 없이 실패를 줄 수 있다. 여기서 터지면 화면이 통째로 죽는다.
      jest.spyOn(api, "patch").mockRejectedValue({ response: { status: 500 } });

      const result = await changeDeliveryAddress(1, {});

      expect(result.ok).toBe(false);
      expect(result.message).toBe("배송지를 변경하지 못했습니다");
    });

    it("survives a network error with no response at all", async () => {
      jest.spyOn(api, "patch").mockRejectedValue(new Error("Network Error"));

      await expect(changeDeliveryAddress(1, {})).resolves.toMatchObject({ ok: false });
    });
  });

  describe("cancelDelivery", () => {
    it("flags 409 as alreadyDispatched", async () => {
      jest.spyOn(api, "post").mockRejectedValue({
        response: { status: 409, data: { message: "이미 발송되어 취소할 수 없습니다" } },
      });

      const result = await cancelDelivery(1);

      expect(result).toMatchObject({ ok: false, alreadyDispatched: true });
    });

    it("reports success", async () => {
      jest.spyOn(api, "post").mockResolvedValue({ data: { cancelled: true } });

      await expect(cancelDelivery(1)).resolves.toMatchObject({ ok: true, cancelled: true });
    });
  });

  describe("fetchDeliveryByOrder", () => {
    it("returns the delivery payload", async () => {
      jest.spyOn(api, "get").mockResolvedValue({ data: { orderId: 5, shipments: [] } });

      await expect(fetchDeliveryByOrder(5)).resolves.toEqual({ orderId: 5, shipments: [] });
    });

    it("propagates failures so the caller can decide", async () => {
      // 이 함수는 오류를 삼키지 않는다 — 호출부(DeliveryTracker)가 "아직 배송 없음"과
      // 진짜 오류를 구분해 처리한다.
      jest.spyOn(api, "get").mockRejectedValue(new Error("404"));

      await expect(fetchDeliveryByOrder(5)).rejects.toThrow();
    });
  });
});
