import {
  paymentMethods, paymentMethodOf, paymentMethodName, paymentMethodDescription,
} from "components/checkout/paymentMethods";

/**
 * 🐛 결제수단 선택이 <b>화면에만 있고 아무 동작도 하지 않았다.</b>
 *
 * <p>주문 화면이 결제수단과 무관하게 PayPal 버튼만 그렸다 — 분기 조건이 주석 처리된
 * 채였다. 그래서 "무통장입금"을 골라도 PayPal 버튼이 떴다.
 *
 * <p>이름에도 오타가 둘 있었다({@code "Creadit cart"}).
 */
describe("결제수단 목록", () => {
  it("id 는 서버로 가는 값이라 바뀌면 안 된다", () => {
    // 🔴 이 값들은 주문의 paymentMethod 로 저장된다. 바꾸면 기존 주문과 어긋난다.
    expect(paymentMethods.map((m) => m.id))
        .toEqual(["paypal", "stripe", "portone", "credit_card", "cash"]);
  });

  it("이름에 오타가 없다", () => {
    const names = paymentMethods.map((m) => paymentMethodName(m));

    expect(names).not.toContain("Creadit cart");
    for (const name of names) {
      expect(name).not.toMatch(/creadit|mastedcard/i);
    }
  });

  /**
   * 🔴 쓸 수 있는 것과 없는 것이 구분돼야 한다. 화면이 이 값을 보고 버튼을 그릴지
   * 안내 문구를 띄울지 정한다 — 전부 available 이면 예전처럼 "골랐는데 아무 일도
   * 일어나지 않는" 상태로 돌아간다.
   */
  it("실제로 결제할 수 있는 수단이 표시돼 있다", () => {
    const usable = paymentMethods.filter((m) => m.available).map((m) => m.id);

    expect(usable).toEqual(["paypal", "stripe", "portone", "cash"]);
  });

  /** 준비 중인 수단은 왜 못 쓰는지 말해야 한다 — 빈 설명이면 화면이 빈 칸을 띄운다. */
  it("준비 중인 수단에는 설명이 있다", () => {
    for (const method of paymentMethods.filter((m) => !m.available)) {
      expect(paymentMethodDescription(method).trim()).not.toBe("");
      // 사전에 없는 키면 키 이름이 그대로 나온다 — 그것도 빈 설명과 같다.
      expect(paymentMethodDescription(method)).not.toBe(method.descriptionKey);
    }
  });

  /** 🔴 이름·설명은 그릴 때의 언어로 — 모듈에 문구를 담아 두면 처음 언어로 굳는다(docs/60 §3). */
  it("이름·설명은 넘겨받은 t 로 번역하고, 상표는 번역하지 않는다", () => {
    const t = (key) => `[${key}]`;

    expect(paymentMethodName(paymentMethodOf("paypal"), t)).toBe("PayPal");
    expect(paymentMethodName(paymentMethodOf("cash"), t)).toBe("[payment.method.cash.name]");
    expect(paymentMethodDescription(paymentMethodOf("stripe"), t)).toBe("[payment.method.stripe.description]");
    expect(paymentMethodName(paymentMethodOf("cash"))).toBe("무통장입금");
  });

  it("id 로 찾을 수 있다", () => {
    expect(paymentMethodOf("stripe").name).toBe("Stripe");
    expect(paymentMethodOf("없는수단")).toBeUndefined();
  });

  /**
   * 🔴 아이콘은 public/ 의 **실제 파일**이어야 한다. 예전에는 화면이
   * `/assets/images/${id}.png` 를 조립했는데 Stripe·포트원에는 그 PNG 가 없어 깨진 채 떴다.
   * 번들러는 문자열 경로를 검사하지 않으므로 여기서 파일 유무를 본다.
   */
  it("모든 수단의 아이콘 파일이 public/ 에 있다", () => {
    const fs = require("fs");
    const path = require("path");
    for (const method of paymentMethods) {
      expect(method.icon).toMatch(/^\/assets\/images\/[a-z_]+\.(png|svg)$/);
      const file = path.resolve(__dirname, "../../../public", method.icon.slice(1));
      expect(fs.existsSync(file)).toBe(true);
    }
  });

  /** 카드 브랜드 로고 파일명 — "mastedcard" 오타가 있던 자리다. */
  it("카드 브랜드 이미지 이름이 실제 파일과 맞는다", () => {
    const card = paymentMethodOf("credit_card");

    expect(card.images).toContain("mastercard");
    expect(card.images).not.toContain("mastedcard");
  });
});
