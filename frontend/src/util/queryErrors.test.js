import { retryNetworkErrors, rootRelative, throwWithoutData, queryClient } from "util/api";

/**
 * 조회 실패의 처리 — 그려 둔 화면을 오류 화면으로 덮지 않는다.
 * 🐛 주문 화면이 결제 확정을 기다리며 다시 읽다 한 번 끊기면 "問題が発生しました / Network Error" 로 통째로 바뀌었다.
 */
describe("조회 실패 정책", () => {
    const networkError = { message: "Network Error" };          // 응답 없음
    const serverError = { response: { status: 400 } };

    test("🔴 그려 둔 데이터가 있으면 오류 화면으로 올리지 않는다(알림만)", () => {
        expect(throwWithoutData(networkError, { state: { data: { id: "1" } } })).toBe(false);
    });

    test("처음 읽기부터 실패하면 오류 화면 — 그릴 것이 없다", () => {
        expect(throwWithoutData(networkError, { state: { data: undefined } })).toBe(true);
    });

    test("응답 없는 실패만 두 번 더 — 서버가 답한 오류는 다시 하지 않는다", () => {
        expect(retryNetworkErrors(0, networkError)).toBe(true);
        expect(retryNetworkErrors(1, networkError)).toBe(true);
        expect(retryNetworkErrors(2, networkError)).toBe(false);
        expect(retryNetworkErrors(0, serverError)).toBe(false);
    });

    test("전역 기본값이 이 정책이다", () => {
        const q = queryClient.getDefaultOptions().queries;
        expect(q.throwOnError).toBe(throwWithoutData);
        expect(q.retry).toBe(retryNetworkErrors);
    });
});


/** 🐛 상품 화면에서 카테고리로 가면 검색이 `/product/catalog-service/...` 로 나가 결과가 비었다. */
describe("요청 경로는 사이트 루트 기준", () => {
    test("🔴 맨 앞 / 를 빠뜨린 경로를 루트 기준으로", () => {
        expect(rootRelative("catalog-service/api/search")).toBe("/catalog-service/api/search");
    });
    test("이미 루트 기준이거나 절대 주소면 그대로", () => {
        expect(rootRelative("/order-service/api/order/1")).toBe("/order-service/api/order/1");
        expect(rootRelative("https://www.paypal.com/sdk/js")).toBe("https://www.paypal.com/sdk/js");
        expect(rootRelative(undefined)).toBeUndefined();
    });
});
