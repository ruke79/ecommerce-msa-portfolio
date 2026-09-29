import {
    amountString,
    chargeOf,
    formatMoney,
    fractionDigits,
    portOneCurrencyCode,
} from "util/money";

/**
 * 결제 화면이 제공자에게 넘기는 것은 청구 통화·금액이다 — 상점 통화의 total 이 아니다.
 */
describe("chargeOf", () => {
    it("청구 통화가 있으면 그것을 쓴다", () => {
        expect(chargeOf({ total: 59, currency: "JPY", chargedAmount: 8850 }))
            .toEqual({ currency: "JPY", amount: 8850 });
    });

    it("옛 주문(청구 통화 없음)은 상점 통화로 total", () => {
        expect(chargeOf({ total: 59 })).toEqual({ currency: "USD", amount: 59 });
        expect(chargeOf({ total: 59, currency: null, chargedAmount: null }))
            .toEqual({ currency: "USD", amount: 59 });
    });

    it("소수 문자열로 온 청구액도 숫자다", () => {
        expect(chargeOf({ currency: "USD", chargedAmount: "59.98" }).amount).toBe(59.98);
    });
});

/** 🔴 JPY·KRW 는 소수가 없다. "1500.00" 은 PayPal 이 거절하고, ×100 은 Stripe 에서 100배다. */
describe("통화별 소수 자릿수", () => {
    it("JPY · KRW 는 0, USD 는 2", () => {
        expect(fractionDigits("JPY")).toBe(0);
        expect(fractionDigits("KRW")).toBe(0);
        expect(fractionDigits("USD")).toBe(2);
    });

    it("PayPal 문자열 — JPY 는 소수 없이, USD 는 둘째 자리까지", () => {
        expect(amountString(1500, "JPY")).toBe("1500");
        expect(amountString(59.98, "USD")).toBe("59.98");
        expect(amountString(59, "USD")).toBe("59.00");
    });

    it("사람에게는 통화 기호로", () => {
        expect(formatMoney(1500, "JPY", "ja-JP")).toMatch(/1,500/);
        expect(formatMoney(80000, "KRW", "ko-KR")).toMatch(/80,000/);
        expect(formatMoney(59.98, "USD", "en-US")).toBe("$59.98");
    });

    it("포트원 V2 통화 코드", () => {
        expect(portOneCurrencyCode("krw")).toBe("CURRENCY_KRW");
        expect(portOneCurrencyCode("JPY")).toBe("CURRENCY_JPY");
    });
});
