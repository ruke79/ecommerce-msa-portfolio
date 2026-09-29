import api, { putCartItem, removeCartItem, retryOnceOnNetworkError, setCartItemQty } from "util/api";

/**
 * 장바구니의 담기·수량·삭제는 응답이 **아예 없을 때만** 한 번 더 보낸다(셋 다 멱등 — 수량을 "이 값으로").
 *
 * <p>🐛 PC 의 다른 컨테이너가 재시작을 반복해 네트워크가 바뀔 때마다(ERR_NETWORK_CHANGED) 휴지통 첫 클릭이
 * "カートを更新できませんでした" 로 실패했다. 다시 누르면 됐다 — 사용자가 할 일을 화면이 대신한다.
 */
const networkError = () => Object.assign(new Error("Network Error"), { code: "ERR_NETWORK" });
const serverError = (status) => Object.assign(new Error("Request failed"), { response: { status, data: { message: "x" } } });

describe("장바구니 요청 — 네트워크 오류면 한 번 더", () => {
    afterEach(() => jest.restoreAllMocks());

    it("🔴 응답 없는 실패는 한 번 더 보내고, 그 결과를 돌려준다", async () => {
        const send = jest.fn().mockRejectedValueOnce(networkError()).mockResolvedValueOnce("ok");
        await expect(retryOnceOnNetworkError(send, 0)).resolves.toBe("ok");
        expect(send).toHaveBeenCalledTimes(2);
    });

    it("두 번째도 실패하면 그 오류를 올린다 — 더 반복하지 않는다", async () => {
        const second = networkError();
        const send = jest.fn().mockRejectedValueOnce(networkError()).mockRejectedValueOnce(second);
        await expect(retryOnceOnNetworkError(send, 0)).rejects.toBe(second);
        expect(send).toHaveBeenCalledTimes(2);
    });

    it.each([400, 401, 404, 409, 503])("서버가 답한 %s 는 다시 보내지 않는다", async (status) => {
        const send = jest.fn().mockRejectedValue(serverError(status));
        await expect(retryOnceOnNetworkError(send, 0)).rejects.toHaveProperty("response.status", status);
        expect(send).toHaveBeenCalledTimes(1);
    });

    it("취소된 요청은 다시 보내지 않는다", async () => {
        const send = jest.fn().mockRejectedValue(Object.assign(new Error("canceled"), { code: "ERR_CANCELED" }));
        await expect(retryOnceOnNetworkError(send, 0)).rejects.toThrow("canceled");
        expect(send).toHaveBeenCalledTimes(1);
    });

    it("🔴 담기·수량·삭제 셋 다 이 규칙을 쓴다", async () => {
        const put = jest.spyOn(api, "put").mockRejectedValueOnce(networkError()).mockResolvedValueOnce({ data: ["a"] });
        const patch = jest.spyOn(api, "patch").mockRejectedValueOnce(networkError()).mockResolvedValueOnce({ data: ["b"] });
        const del = jest.spyOn(api, "delete").mockRejectedValueOnce(networkError()).mockResolvedValueOnce({ data: ["c"] });

        // 실제 대기(0.6초)를 셋이 함께 기다린다
        await expect(Promise.all([
            putCartItem({ id: "1", qty: 1 }),
            setCartItemQty({ productId: "1", colorId: "5", size: "M", qty: 2 }),
            removeCartItem({ productId: "1", colorId: "5", size: "M" }),
        ])).resolves.toEqual([["a"], ["b"], ["c"]]);
        expect(put).toHaveBeenCalledTimes(2);
        expect(patch).toHaveBeenCalledTimes(2);
        expect(del).toHaveBeenCalledTimes(2);
    });
});
