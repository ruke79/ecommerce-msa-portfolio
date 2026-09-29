-- 구매 확정 (docs/25 §12).
--
-- OrderStatus.COMPLETED 는 처음부터 선언돼 있었지만 **그 값을 넣는 코드가 저장소 어디에도
-- 없었다.** 배달 완료(DELIVERED)가 마지막이었고, 손님이 확정하거나 기한이 지나 자동으로
-- 확정되는 길이 없었다 — PROCESSING·DISPATCHED·DELIVERED 가 비어 있던 것과 같은 모양이다.
--
-- 주문에 둘을 남긴다:
--   completed_at    확정 시각. 손님이 눌렀든 기한이 지나 자동으로 됐든 여기 찍힌다.
--   auto_completed  자동 확정이면 true. 화면은 "N일이 지나 자동으로 확정되었습니다" 라고 말한다.
--
-- 추가만 한다. completed_at 은 nullable, auto_completed 는 DEFAULT 가 있어 옛 이미지의
-- INSERT 도 통과한다. 되돌리기는 안전하다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS completed_at   timestamp;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS auto_completed boolean NOT NULL DEFAULT false;
