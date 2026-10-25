import { newPortOnePaymentId, portOneCustomer, portOneRedirectUrl, portOneReturnOf, portOneStoreDetails, portOneSupportsVirtualAccount, portOneVersion } from "util/portone";

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

/**
 * V2 리디렉션 — 돌아올 주소는 지금 주문 화면(쿼리 · 해시 없이), 돌아온 쿼리는 우리 결제 id 일 때만 읽는다.
 *
 * <p>[JA] V2 リダイレクト — 戻り先は今の注文画面(クエリ · ハッシュなし)、戻ったクエリは自前の決済 id のときだけ読む。
 */
describe("portOneRedirectUrl · portOneReturnOf", () => {
    it("돌아올 주소에는 쿼리 · 해시를 싣지 않는다 — 지난번 복귀 쿼리가 다시 붙지 않게", () => {
        expect(portOneRedirectUrl({ origin: "https://www.ysyproject.com", pathname: "/order/42" }))
            .toBe("https://www.ysyproject.com/order/42");
    });

    it("성공 복귀 — 결제 id 만, code 없음", () => {
        expect(portOneReturnOf(new URLSearchParams("paymentId=payment-abc&transactionType=PAYMENT&txId=tx-1")))
            .toEqual({ paymentId: "payment-abc", code: undefined, message: undefined, pgCode: undefined, pgMessage: undefined });
    });

    it("실패 복귀 — code · message · PG 문구", () => {
        expect(portOneReturnOf(new URLSearchParams("paymentId=payment-abc&code=FAILURE_TYPE_PG&message=m&pgCode=01&pgMessage=pm")))
            .toEqual({ paymentId: "payment-abc", code: "FAILURE_TYPE_PG", message: "m", pgCode: "01", pgMessage: "pm" });
    });

    it("우리가 만든 결제 id 가 아니면(또는 없으면) 복귀가 아니다", () => {
        expect(portOneReturnOf(new URLSearchParams("paymentId=other-1"))).toBeNull();
        expect(portOneReturnOf(new URLSearchParams("stripe=success"))).toBeNull();
    });
});

/**
 * 🐛 2026-10-24 운영: "이니시스 일본 결제의 경우 구매자 이름은 필수 입력입니다" — 로그인 사용자 객체(JWT)에는 이름이 없어 언제나 비어 있었다.
 *
 * <p>[JA] 「イニシス日本決済は購入者名が必須」— ログイン利用者オブジェクト(JWT)に名前がなく常に空だった。
 */
describe("portOneCustomer", () => {
    const user = { email: "buyer@example.com", userId: "1", roles: [] };

    it("🔴 이름은 배송지의 성 · 이름을 fullName 하나로 — firstName/lastName 은 보내지 않는다(뜻이 반대)", () => {
        expect(portOneCustomer({ shippingAddress: { firstname: " 山田 ", lastname: "太郎", phoneNumber: "090-1234-5678" } }, user))
            .toEqual({ email: "buyer@example.com", fullName: "山田 太郎", phoneNumber: "09012345678" });
    });

    it("🔴 전화번호는 숫자만 — 하이픈 · 공백 · + 를 뺀다(포트원 예시 `01012341234`)", () => {
        expect(portOneCustomer({ shippingAddress: { phoneNumber: "+81 90-1234-5678" } }, user).phoneNumber).toBe("819012345678");
        expect(portOneCustomer({ shippingAddress: { phoneNumber: " - " } }, user).phoneNumber).toBeUndefined();
        expect(portOneCustomer({ shippingAddress: {} }, user).phoneNumber).toBeUndefined();
    });

    it("배송지에 이름이 없으면(옛 주소) 이메일 앞부분 — 빈 이름으로 보내지 않는다", () => {
        expect(portOneCustomer({ shippingAddress: {} }, user).fullName).toBe("buyer");
        expect(portOneCustomer({}, user).fullName).toBe("buyer");
    });
});

describe("portOneSupportsVirtualAccount", () => {
    it("가상계좌는 국내(KRW) 채널에만 — 이니시스 일본결제(JPY)에는 없다", () => {
        expect(portOneSupportsVirtualAccount("KRW")).toBe(true);
        expect(portOneSupportsVirtualAccount("krw")).toBe(true);
        expect(portOneSupportsVirtualAccount("JPY")).toBe(false);
        expect(portOneSupportsVirtualAccount(undefined)).toBe(false);
    });
});

/**
 * 이니시스 일본결제(CBT JPPG)는 상점 정보가 필수 — JPY 청구일 때만 싣는다.
 *
 * <p>[JA] イニシス日本決済(CBT JPPG)は店舗情報が必須 — JPY 請求のときだけ載せる。
 */
describe("portOneStoreDetails", () => {
    it("🔴 JPY — 상점명(가나 · 영문 · 약어) · 담당 · 연락처(숫자만) · 영업시간 · 이메일", () => {
        expect(portOneStoreDetails("JPY", " store@example.com ")).toEqual({
            storeName: "アマス", storeNameKana: "アマス", storeNameEn: "Amasu", storeNameShort: "アマス",
            contactName: "アマス", phoneNumber: "0810700000001", openingHours: { open: "00:00", close: "23:59" },
            email: "store@example.com",
        });
    });

    it("영문 이름은 알파벳만 · 가나는 가타카나만(포트원 규칙)", () => {
        const d = portOneStoreDetails("JPY", "s@example.com");
        expect(d.storeNameEn).toMatch(/^[A-Za-z ]+$/);
        expect(d.storeNameKana).toMatch(/^[\u30A0-\u30FF]+$/);
    });

    it("이메일 빌드 변수가 비면 그 칸을 빼고, JPY 가 아니면 싣지 않는다", () => {
        expect(portOneStoreDetails("JPY", "")).not.toHaveProperty("email");
        expect(portOneStoreDetails("KRW", "s@example.com")).toBeUndefined();
        expect(portOneStoreDetails(undefined, "s@example.com")).toBeUndefined();
    });
});
