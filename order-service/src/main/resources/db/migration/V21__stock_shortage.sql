-- 재고 부족 — 결제됐지만 보낼 수 없는 수량 (docs/53 §초과 판매).
--
-- 재고는 결제 뒤에 차감한다. 마지막 1개를 두 사람이 동시에 결제하면 catalog 가 뒤의 주문에 "n개 모자라다" 를
-- 알린다(stock-shortage). order 는 그 수를 줄마다 short_qty 로 적고 자동 환불한다.
--
-- 왜 따로 세나: 발송 전 환불은 수량을 "돌아왔다"(returned_qty)로 세고 재고 복원을 보낸다. 그런데 모자란 수는
-- **차감된 적이 없다** — 돌아왔다고 세면 catalog 에 없는 재고가 생긴다. short_qty 는 복원·반품·배송 수량에서 빠진다.
--
-- shortage_event_id: 같은 부족 알림의 재전달을 버리는 멱등 키. shortage_resolved_at: 자동 환불이 끝났거나
-- 운영자에게 넘긴 시각 — 비어 있으면 재시도 대상이다.
--
-- 되돌리기 안전: 컬럼 추가뿐이다. 옛 이미지는 모르는 컬럼을 무시한다.

ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS short_qty integer NOT NULL DEFAULT 0;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS shortage_event_id varchar(80);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS shortage_resolved_at timestamp(6);
