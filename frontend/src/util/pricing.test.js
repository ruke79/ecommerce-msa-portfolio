import { discounted, formatMoney, roundMoney, skuIndexInPriceRange, skuPriceRange, splitCartTotal } from "util/pricing";

/**
 * 🐛 회귀 대상: 할인가 계산식이 `price - price / discount` 였다.
 *
 * `discount` 는 퍼센트(시드 데이터가 5~70 을 넣는다)인데 나눗셈을 쓰는 바람에 의미가 없는
 * 값이 나왔고, **할인율이 클수록 할인 폭이 작아지는** 거꾸로 된 동작이었다.
 * 10,000원짜리에 50% 할인 → 9,800원(=2% 할인), 70% 할인 → 9,857원(=1.4% 할인).
 *
 * 사용자에게 표시되는 가격이라 조용히 틀리면 신뢰를 잃는 종류의 버그다.
 */
describe("discounted", () => {
  it("applies the percentage, not a division", () => {
    // 옛 공식으로는 9800 이 나온다. 이 한 줄이 버그를 못박는다.
    expect(discounted(10000, 50)).toBe(5000);
  });

  it("discounts more as the percentage grows", () => {
    // 옛 공식은 이 관계가 반대였다.
    const at30 = discounted(10000, 30);
    const at70 = discounted(10000, 70);
    expect(at70).toBeLessThan(at30);
    expect(at30).toBe(7000);
    expect(at70).toBeCloseTo(3000, 6);
  });

  it("returns the original price when there is no discount", () => {
    expect(discounted(12345, 0)).toBe(12345);
    expect(discounted(12345, null)).toBe(12345);
    expect(discounted(12345, undefined)).toBe(12345);
  });

  it("never produces a negative price even on bad data", () => {
    // 데이터 오류로 100 을 넘는 할인율이 들어와도 가격이 음수가 되면 안 된다.
    expect(discounted(10000, 150)).toBe(0);
  });

  it("coerces numeric strings, which is what the API actually sends", () => {
    expect(discounted("10000", "50")).toBe(5000);
  });

  it("treats a missing price as zero rather than NaN", () => {
    expect(discounted(undefined, 50)).toBe(0);
  });
});

/**
 * 장바구니 총액을 상품 금액과 배송비로 나눈다.
 *
 * <p>🔴 <b>예전에는 나눌 수가 없었다.</b> cart-service 가 `price×qty + shipping` 으로
 * 합산한 총액 하나만 내려보냈고, 결제 화면은 "Total" 한 줄이 전부였다 — 사용자는 자기가
 * 배송비를 얼마 내는지 <b>주문하는 순간까지 몰랐다.</b>
 */
describe("splitCartTotal — 총액을 상품 금액과 배송비로", () => {
  const cart = (cartTotal, products) => ({ cartTotal, products });

  it("배송비를 총액에서 빼 상품 금액을 낸다", () => {
    const r = splitCartTotal(cart(13000, [{ price: 10000, qty: 1, shipping: 3000 }]));

    expect(r.merchandise).toBe(10000);
    expect(r.shipping).toBe(3000);
    expect(r.total).toBe(13000);
  });

  it("줄이 여럿이면 배송비를 합산한다", () => {
    const r = splitCartTotal(cart(26000, [
      { price: 10000, qty: 1, shipping: 3000 },
      { price: 10000, qty: 1, shipping: 3000 },
    ]));

    expect(r.shipping).toBe(6000);
    expect(r.merchandise).toBe(20000);
  });

  /** 배송비는 수량과 무관한 줄 단위 금액이다 — 서버의 computeCartTotal 과 같은 규칙. */
  it("배송비에 수량을 곱하지 않는다 — 서버와 같은 규칙이어야 한다", () => {
    const r = splitCartTotal(cart(33000, [{ price: 10000, qty: 3, shipping: 3000 }]));

    expect(r.shipping).toBe(3000);
    expect(r.merchandise).toBe(30000);
  });

  /** 세 줄이 서로 맞지 않으면 화면이 거짓말을 한다. */
  it("상품 금액 + 배송비 = 합계", () => {
    const r = splitCartTotal(cart(27000, [{ price: 24500, qty: 1, shipping: 2500 }]));

    expect(r.merchandise + r.shipping).toBe(r.total);
  });

  /**
   * 옛 장바구니 캐시에는 shipping 필드가 없다. 배송비를 **없는 것으로** 보는 쪽이
   * 안전하다 — 반대로 추측하면 상품 금액이 실제보다 작게 표시된다.
   */
  it("배송비 정보가 없으면 0으로 본다", () => {
    const r = splitCartTotal(cart(10000, [{ price: 10000, qty: 1 }]));

    expect(r.shipping).toBe(0);
    expect(r.merchandise).toBe(10000);
  });

  it("장바구니가 비어 있거나 망가져 있어도 터지지 않는다", () => {
    expect(splitCartTotal(undefined)).toEqual({ merchandise: 0, shipping: 0, total: 0 });
    expect(splitCartTotal({})).toEqual({ merchandise: 0, shipping: 0, total: 0 });
    expect(splitCartTotal(cart(100, null)).merchandise).toBe(100);
  });

  /** 배송비가 총액보다 클 수는 없지만, 데이터가 어긋나도 음수를 보여 주지는 않는다. */
  it("상품 금액이 음수가 되지 않는다", () => {
    const r = splitCartTotal(cart(1000, [{ price: 0, qty: 1, shipping: 5000 }]));

    expect(r.merchandise).toBe(0);
  });
});

/**
 * 🐛 <b>결제 화면의 배송비가 소수점 열몇 자리로 나왔다.</b>
 *
 * <p>{@code splitCartTotal} 이 {@code total - shipping} 을 그대로 내보냈는데, 둘 다
 * 부동소수라 {@code 20.01 - 6.66} 이 {@code 13.350000000000001} 이 된다. 화면은 그 값을
 * 받아 그대로 찍었다.
 *
 * <p>⚠️ 예시를 고를 때 주의할 것: {@code 13.8 - 4.9} 는 <b>정확히 8.9 다.</b> 처음 이
 * 가드를 그 값으로 썼다가 <b>수정을 되돌려도 통과</b>하는 것을 확인하고 바꿨다. 부동소수
 * 테스트는 실제로 어긋나는 값을 골라야 가드가 된다.
 *
 * <p>장바구니 화면은 {@code .toFixed(2)} 를 쓰고 있어서 <b>같은 계산인데 한쪽만</b>
 * 이상하게 보였다 — 어디가 문제인지 알아채기 어려운 종류다.
 */
describe("formatMoney / roundMoney — 돈은 센트에서 멈춘다", () => {
  it("부동소수 꼬리를 자른다", () => {
    expect(20.01 - 6.66).not.toBe(13.35);          // 전제: 실제로 어긋난다
    expect(roundMoney(20.01 - 6.66)).toBe(13.35);
    expect(formatMoney(20.01 - 6.66)).toBe("13.35");
    expect(formatMoney(0.1 + 0.2)).toBe("0.3");
  });

  it("셋째 자리에서 반올림한다", () => {
    expect(formatMoney(12.345)).toBe("12.35");
    expect(formatMoney(12.344)).toBe("12.34");
    // 1.005 는 실제로 1.00499… 로 저장된다. EPSILON 보정이 없으면 "1"이 된다.
    expect(formatMoney(1.005)).toBe("1.01");
  });

  /** 정수 가격이 대부분인 카탈로그에서 "4.00" 은 시끄럽다. */
  it("불필요한 0 은 붙이지 않는다", () => {
    expect(formatMoney(4)).toBe("4");
    expect(formatMoney(4.5)).toBe("4.5");
    expect(formatMoney(0)).toBe("0");
  });

  it("숫자가 아니면 0 으로 본다 — NaN$ 가 화면에 뜨면 안 된다", () => {
    expect(formatMoney(undefined)).toBe("0");
    expect(formatMoney(null)).toBe("0");
    expect(formatMoney("abc")).toBe("0");
    expect(formatMoney(Infinity)).toBe("0");
  });

  it("숫자 문자열은 받아들인다 — API 가 그렇게 보낸다", () => {
    expect(formatMoney("12.3456")).toBe("12.35");
  });

  /** 🔴 핵심. 계산 단계에서 고정하지 않으면 화면마다 다시 반올림해야 한다. */
  it("splitCartTotal 이 꼬리를 남기지 않는다", () => {
    const amounts = splitCartTotal({
      cartTotal: 20.01,
      products: [{ shipping: 6.66 }],
    });

    expect(amounts.merchandise).toBe(13.35);
    expect(String(amounts.merchandise)).not.toContain("00000");
  });

  /** 세 줄을 각각 반올림하면 1센트가 어긋날 수 있다. 계산 단계에서 맞춰 둔다. */
  it("반올림 후에도 상품 금액 + 배송비 = 합계", () => {
    const amounts = splitCartTotal({
      cartTotal: 20.01,
      products: [{ shipping: 3.33 }, { shipping: 3.33 }],
    });

    expect(roundMoney(amounts.merchandise + amounts.shipping)).toBe(amounts.total);
  });
});

/**
 * 🔴 서버와 같은 값이어야 한다 — catalog 의 ProductService.discounted 는 price × (100 − d) / 100 을 소수 둘째
 * 자리에서 HALF_UP 으로 자르고, 그 값이 장바구니에 담겨 청구된다. 예전 식(부동소수 p × (1 − d/100))은 $1.15 의
 * 50% 를 0.57 로 그렸다(서버는 0.58) — 상품 화면과 결제 금액이 1센트 달랐다.
 */
describe("discounted — 서버와 같은 반올림", () => {
  it.each([
    [1.15, 50, 0.58],
    [10, 7, 9.3],
    [19.99, 15, 16.99],
    [0.05, 50, 0.03],
    [59.98, 10, 53.98],
  ])("%p 의 %p%% 할인은 %p", (price, pct, expected) => {
    expect(discounted(price, pct)).toBe(expected);
  });
});

describe("skuPriceRange — 목록 카드·상품 화면·정렬이 같이 쓰는 할인가 범위", () => {
  it("할인이 반영된 최저·최고와 정가를 함께 준다", () => {
    expect(skuPriceRange({ discount: 30, sizes: [{ price: 120 }, { price: 100 }] })).toEqual({
      min: 70, max: 84, minBefore: 100, maxBefore: 120, discount: 30,
    });
  });

  it("할인이 없으면 정가 그대로", () => {
    expect(skuPriceRange({ discount: 0, sizes: [{ price: "59.98" }] })).toMatchObject({ min: 59.98, max: 59.98 });
  });

  it("사이즈가 없으면 null — 카드가 흰 화면으로 죽지 않게", () => {
    expect(skuPriceRange({ discount: 10, sizes: [] })).toBeNull();
    expect(skuPriceRange(undefined)).toBeNull();
  });
});

describe("skuIndexInPriceRange — 가격대에 드는 첫 스타일", () => {
    const skus = [
        { discount: 0, sizes: [{ price: 15 }, { price: 29 }] },
        { discount: 30, sizes: [{ price: 100 }] },          // 화면 가격 70 — 정가(100)가 아니라 할인가로 본다(서버와 같다)
        { discount: 0, sizes: [{ price: 120 }] },
    ];
    it("할인가로 비교한다 — 정가 100 이지만 화면 70 이면 $50~$80 에 든다", () => {
        expect(skuIndexInPriceRange(skus, "50", "80")).toBe(1);
    });
    it("열린 범위 — 하한만·상한만", () => {
        expect(skuIndexInPriceRange(skus, "100", "")).toBe(2);
        expect(skuIndexInPriceRange(skus, "", "20")).toBe(0);
    });
    it("맞는 스타일이 없거나 범위가 없으면 -1", () => {
        expect(skuIndexInPriceRange(skus, "500", "")).toBe(-1);
        expect(skuIndexInPriceRange(skus, "", "")).toBe(-1);
        expect(skuIndexInPriceRange(undefined, "1", "2")).toBe(-1);
    });
});
