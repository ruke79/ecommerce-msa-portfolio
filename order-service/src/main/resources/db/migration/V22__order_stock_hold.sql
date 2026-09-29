-- 결제 전 재고 예약의 만료 시각 (docs/59).
--
-- 재고는 catalog 가 잡고(product.stock_reservation), 여기에는 "언제까지 잡혀 있는가" 만 둔다 — 결제 화면이 "이 시간 안에
-- 결제해 주세요" 를 보여 주고, 지나면 "재고가 보장되지 않는다" 고 말하려고. NULL 은 예약이 없다는 뜻이다(이 기능 전의 주문,
-- 또는 catalog 에 닿지 못해 예약 없이 만든 주문 — 그때는 결제 뒤 차감과 부족분 자동 환불이 받는다).
--
-- deposit_deadline 과 같은 timestamp(시간대 없음) — 같은 방식(LocalDateTime)으로 읽고 쓴다.
-- 컬럼 추가만이라 옛 이미지와 호환된다(모르는 컬럼은 읽지 않는다).

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS stock_hold_until timestamp;
