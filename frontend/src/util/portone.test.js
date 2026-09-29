import { newPortOnePaymentId, portOneVersion } from "util/portone";

/**
 * 포트원 버전 선택.
 *
 * <p>V1(imp… 식별코드)과 V2(store-… Store ID)는 SDK·전역·파라미터가 전부 다르다.
 * 어느 쪽으로 빌드됐는지는 <b>빌드 인자가 말한다</b> — Store ID 가 있으면 V2, 아니면
 * 식별코드가 있을 때 V1. 둘 다 있으면 V2 다(신규 계정은 V2 뿐이고, V1 값은 남은 것이다).
 */
describe("portOneVersion", () => {
    it("Store ID 가 있으면 V2", () => {
        expect(portOneVersion({ REACT_APP_PORTONE_STORE_ID: "store-1" })).toBe("v2");
    });

    it("식별코드만 있으면 V1", () => {
        expect(portOneVersion({ REACT_APP_PORTONE_IMP_CODE: "imp12345678" })).toBe("v1");
    });

    it("둘 다 있으면 V2 가 우선한다", () => {
        expect(portOneVersion({
            REACT_APP_PORTONE_STORE_ID: "store-1",
            REACT_APP_PORTONE_IMP_CODE: "imp12345678",
        })).toBe("v2");
    });

    it("둘 다 없으면 설정되지 않은 것이다", () => {
        expect(portOneVersion({})).toBeNull();
    });
});

/** V2 결제 id — 시도마다 새 값이어야 한다(같은 id 로 두 번 결제할 수 없다). */
describe("newPortOnePaymentId", () => {
    it("payment- 로 시작하고 매번 다르다", () => {
        const a = newPortOnePaymentId();
        const b = newPortOnePaymentId();
        expect(a).toMatch(/^payment-/);
        expect(a).not.toBe(b);
    });
});
