/**
 * 주문 상태 판정.
 *
 * <p>서버의 값은 {@code common/constants/OrderStatus.java} 가 정한다:
 * {@code NOT_PROCESSED · PAID · UNPAID · PROCESSING · DISPATCHED · DELIVERED ·
 * CANCELED · COMPLETED}.
 *
 * <p>🐛 화면 여러 곳이 이 값을 문자열로 직접 비교하다 틀렸다. 주문 목록은
 * {@code orderStatus === "UNPAID"} 일 때 초록 체크를 그렸다 — <b>미결제가 결제 완료처럼</b>
 * 보였다. 비교를 한 곳에 모아 둔다.
 */

/**
 * 결제가 끝난 상태들.
 *
 * <p>"미결제가 아닌 것"으로 판정하지 않는 이유: {@code NOT_PROCESSED} 와 {@code CANCELED}
 * 도 결제된 상태가 아니다. 결제된 쪽을 열거하면 <b>새 상태가 추가돼도 기본값이 "미결제"</b>
 * 라 안전한 쪽으로 틀린다. 반대로 하면 새 상태가 전부 "결제 완료"로 보인다.
 *
 * <p>⚠️ 이 집합은 서버의 {@code OrderStatus.PAID_STATUSES} 와 <b>같아야 한다.</b> 그쪽은
 * 매출·구매 이력·등급 산정의 기준이라, 여기와 어긋나면 "주문 내역에는 결제된 주문인데
 * 등급 계산에는 안 잡힌다" 가 된다. 언어가 달라 공유할 수 없으니 한쪽을 고치면 반대쪽도
 * 고쳐야 한다 — 서버 쪽에는 상태가 늘면 실패하는 테스트가 있다(PaidStatusesTest).
 */
const PAID_STATUSES = new Set([
  "PAID",
  "PROCESSING",
  "DISPATCHED",
  "DELIVERED",
  "COMPLETED",
]);

export function isPaid(orderStatus) {
  if (!orderStatus) return false;
  return PAID_STATUSES.has(String(orderStatus).trim().toUpperCase());
}

/** 무통장입금 입금 대기 — 안내를 받았고 기한 안에 돈이 와야 한다(docs/49 §11). */
export function isAwaitingDeposit(orderStatus) {
  return String(orderStatus ?? "").trim().toUpperCase() === "UNPAID";
}

export function isCanceled(orderStatus) {
  return String(orderStatus ?? "").trim().toUpperCase() === "CANCELED";
}

/** 돈이 돌아간 주문. CANCELED(결제 전에 접음)와 다르다. */
export function isRefunded(orderStatus) {
  return String(orderStatus ?? "").trim().toUpperCase() === "REFUNDED";
}

/**
 * 사용자가 <b>스스로</b> 환불 요청을 할 수 있는 상태 — 물건이 아직 나가지 않았다.
 *
 * <p>서버의 {@code OrderDataService.SELF_SERVICE_REFUNDABLE} 과 같아야 한다. 발송된
 * 뒤에는 물건이 돌아와야 하므로 버튼 대신 고객센터 안내를 그린다 — 서버가 어차피
 * 거절하지만, 눌러 보고 거절당하는 것보다 처음부터 없는 편이 낫다.
 */
const SELF_SERVICE_REFUNDABLE = new Set(["PAID", "PROCESSING"]);

export function canRequestRefund(orderStatus) {
  if (!orderStatus) return false;
  return SELF_SERVICE_REFUNDABLE.has(String(orderStatus).trim().toUpperCase());
}

/**
 * 물건이 창고를 떠난 상태 — 반품 입고를 기록할 수 있다. 서버의
 * {@code OrderDataService.SHIPPED_STATUSES} 와 같아야 한다.
 */
const SHIPPED_STATUSES = new Set(["DISPATCHED", "DELIVERED", "COMPLETED"]);

/** 물건이 도착한 주문 — 환불은 반품 절차를 거친다. */
export function isDelivered(orderStatus) {
  const s = String(orderStatus ?? "").trim().toUpperCase();
  return s === "DELIVERED" || s === "COMPLETED";
}

export function isShipped(orderStatus) {
  if (!orderStatus) return false;
  return SHIPPED_STATUSES.has(String(orderStatus).trim().toUpperCase());
}

/**
 * PAID → PROCESSING → DISPATCHED → DELIVERED → COMPLETED 에서 몇 번째인가. 그 밖은 -1.
 *
 * <p>서버의 {@code OrderDeliveryStatusService.rank()} 와 같은 순서다. 배송 상태가 바뀐 뒤
 * 주문이 "그 단계까지 왔는가" 를 판정할 때 쓴다 — 주문은 Kafka 왕복 뒤에 바뀌므로 화면이
 * 따라잡을 때까지 다시 읽어야 한다.
 */
const ORDER_RANK = ["PAID", "PROCESSING", "DISPATCHED", "DELIVERED", "COMPLETED"];

export function orderRank(orderStatus) {
  return ORDER_RANK.indexOf(String(orderStatus ?? "").trim().toUpperCase());
}

/**
 * 배송 단계(delivery.stage)가 주문을 어디까지 데려가는가. 서버의
 * {@code OrderDeliveryStatusService.targetOf} 의 stage 분기와 같다. 취소·반송처럼 주문을
 * 앞으로 움직이지 않는 단계는 null.
 */
export function rankForDeliveryStage(stage) {
  switch (String(stage ?? "").trim().toUpperCase()) {
    case "ORDERED":
      return orderRank("PROCESSING");
    case "SHIPPED":
    case "IN_TRANSIT":
    case "CUSTOMS":
    case "EXCEPTION":
      return orderRank("DISPATCHED");
    case "DELIVERED":
      return orderRank("DELIVERED");
    default:
      return null;
  }
}

/** 구매가 확정된 주문 — 주문의 마지막 상태(docs/25 §12). */
export function isCompleted(orderStatus) {
  return String(orderStatus ?? "").trim().toUpperCase() === "COMPLETED";
}

/**
 * 손님이 구매 확정을 누를 수 있는 상태 — <b>배달 완료뿐</b>이다.
 *
 * <p>서버의 {@code OrderPurchaseConfirmationService} 와 같아야 한다. 이미 확정됐으면(COMPLETED)
 * 버튼 대신 완료 표시를 그리고, 그 밖의 상태에는 아무것도 그리지 않는다.
 */
export function canConfirmPurchase(orderStatus) {
  return String(orderStatus ?? "").trim().toUpperCase() === "DELIVERED";
}

export { PAID_STATUSES, SELF_SERVICE_REFUNDABLE, SHIPPED_STATUSES };
