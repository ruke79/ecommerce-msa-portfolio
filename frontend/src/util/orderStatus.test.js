import { isPaid, isCanceled, isRefunded, canRequestRefund } from "util/orderStatus";

/**
 * 🐛 회귀 대상: **주문 목록의 결제 표시가 거꾸로였다.**
 *
 * `pages/profile/orders.jsx` 가 `orderStatus === "UNPAID"` 일 때 초록 체크를 그렸다.
 * 미결제가 결제 완료처럼 보이고, 실제로 결제된 주문에는 빨간 X 가 붙었다.
 */
describe("isPaid — 결제된 주문인가", () => {
  it("결제 이후 상태는 전부 결제된 것으로 본다", () => {
    // 배송 중인 주문이 "미결제"로 보이면 안 된다.
    ["PAID", "PROCESSING", "DISPATCHED", "DELIVERED", "COMPLETED"].forEach((s) =>
      expect(isPaid(s)).toBe(true)
    );
  });

  it("UNPAID 는 결제되지 않았다 — 예전에 초록 체크가 붙던 값이다", () => {
    expect(isPaid("UNPAID")).toBe(false);
  });

  /**
   * "UNPAID 가 아니면 결제됨"으로 판정하지 않는 이유가 이것이다. OrderStatus 에는
   * 결제되지 않은 상태가 UNPAID 말고도 있다.
   */
  it("NOT_PROCESSED · CANCELED 도 결제되지 않았다", () => {
    expect(isPaid("NOT_PROCESSED")).toBe(false);
    expect(isPaid("CANCELED")).toBe(false);
  });

  /**
   * 새 상태가 서버에 추가됐을 때 기본값이 "결제 완료"면 결제되지 않은 주문에 초록
   * 체크가 붙는다. 안전한 쪽으로 틀려야 한다.
   */
  it("모르는 상태는 결제되지 않은 것으로 본다", () => {
    expect(isPaid("REFUND_REQUESTED")).toBe(false);
    expect(isPaid("")).toBe(false);
    expect(isPaid(null)).toBe(false);
    expect(isPaid(undefined)).toBe(false);
  });

  it("대소문자와 공백에 흔들리지 않는다", () => {
    expect(isPaid(" paid ")).toBe(true);
    expect(isPaid("Delivered")).toBe(true);
  });
});

describe("isCanceled", () => {
  it("취소된 주문을 알아본다", () => {
    expect(isCanceled("CANCELED")).toBe(true);
    expect(isCanceled("PAID")).toBe(false);
    expect(isCanceled(undefined)).toBe(false);
  });
});

describe("환불", () => {
  it("REFUNDED 는 결제된 상태가 아니다 — 돈이 돌아갔다", () => {
    expect(isRefunded("REFUNDED")).toBe(true);
    expect(isPaid("REFUNDED")).toBe(false);
    expect(isCanceled("REFUNDED")).toBe(false);
  });

  /** 서버의 SELF_SERVICE_REFUNDABLE 과 같아야 한다 — 발송 뒤에는 버튼이 없다. */
  it("발송 전(PAID · PROCESSING)에만 스스로 환불을 요청할 수 있다", () => {
    expect(canRequestRefund("PAID")).toBe(true);
    expect(canRequestRefund("PROCESSING")).toBe(true);
    ["DISPATCHED", "DELIVERED", "COMPLETED", "NOT_PROCESSED", "UNPAID", "CANCELED", "REFUNDED"]
      .forEach((s) => expect(canRequestRefund(s)).toBe(false));
    expect(canRequestRefund(undefined)).toBe(false);
  });
});

describe("구매 확정 (docs/25 §12)", () => {
  const { isCompleted, canConfirmPurchase } = require("util/orderStatus");

  /** 서버의 OrderPurchaseConfirmationService 와 같은 규칙 — 배달 완료에서만 누를 수 있다. */
  it("배달 완료(DELIVERED)에서만 구매 확정을 누를 수 있다", () => {
    expect(canConfirmPurchase("DELIVERED")).toBe(true);
    ["PAID", "PROCESSING", "DISPATCHED", "COMPLETED", "REFUNDED", "CANCELED", "NOT_PROCESSED", "UNPAID", "", null]
      .forEach((s) => expect(canConfirmPurchase(s)).toBe(false));
  });

  it("COMPLETED 는 확정된 주문이고, 결제된 상태이며, 도착한 주문이다", () => {
    expect(isCompleted("COMPLETED")).toBe(true);
    expect(isCompleted(" completed ")).toBe(true);
    expect(isCompleted("DELIVERED")).toBe(false);
    expect(isPaid("COMPLETED")).toBe(true);
  });
});

describe("orderRank / rankForDeliveryStage — 배송 뒤 주문이 따라왔는가", () => {
  const { orderRank, rankForDeliveryStage } = require("util/orderStatus");

  it("PAID → PROCESSING → DISPATCHED → DELIVERED → COMPLETED 순서다", () => {
    expect(orderRank("PAID")).toBeLessThan(orderRank("PROCESSING"));
    expect(orderRank("PROCESSING")).toBeLessThan(orderRank("DISPATCHED"));
    expect(orderRank("DISPATCHED")).toBeLessThan(orderRank("DELIVERED"));
    expect(orderRank("DELIVERED")).toBeLessThan(orderRank("COMPLETED"));
    expect(orderRank("CANCELED")).toBe(-1);
    expect(orderRank(null)).toBe(-1);
  });

  /** 서버의 OrderDeliveryStatusService.targetOf 와 같은 대응이다. */
  it("배송 단계가 주문을 데려가는 자리", () => {
    expect(rankForDeliveryStage("ORDERED")).toBe(orderRank("PROCESSING"));
    ["SHIPPED", "IN_TRANSIT", "CUSTOMS", "EXCEPTION"].forEach((s) =>
      expect(rankForDeliveryStage(s)).toBe(orderRank("DISPATCHED")));
    expect(rankForDeliveryStage("DELIVERED")).toBe(orderRank("DELIVERED"));
    expect(rankForDeliveryStage("CANCELLED")).toBeNull();
    expect(rankForDeliveryStage(undefined)).toBeNull();
  });

  /** 🔴 구매 확정된 주문에 늦게 온 배송 이벤트는 "이미 따라왔다" 다 — 영원히 기다리지 않는다. */
  it("주문이 더 앞서 있으면 이미 따라온 것이다", () => {
    expect(orderRank("COMPLETED") >= rankForDeliveryStage("DELIVERED")).toBe(true);
    expect(orderRank("DISPATCHED") >= rankForDeliveryStage("SHIPPED")).toBe(true);
    expect(orderRank("PROCESSING") >= rankForDeliveryStage("SHIPPED")).toBe(false);
  });
});
