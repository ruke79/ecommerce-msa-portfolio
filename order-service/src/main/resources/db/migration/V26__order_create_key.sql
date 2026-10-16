-- 주문 생성의 멱등 키(create_key) — 같은 결제 시도의 재요청이 주문을 하나 더 만들지 않게.
--
-- 🐛 예전에는 없었다. 게이트웨이 시간 초과(10초) 뒤 화면이 다시 보내거나 손님이 다시 누르면 주문이 둘 생겼고, 둘 다 재고를
-- 잡았다(결제는 하나만 된다 — 남은 하나는 결제 전으로 남아 예약이 풀릴 때까지 재고를 묶었다).
-- 화면이 결제 시도마다 키를 만들어 Idempotency-Key 헤더로 보낸다. 같은 고객 · 같은 키면 기존 주문을 돌려준다.
-- [JA] 注文作成の冪等キー — 同じ決済試行の再送が注文をもう一つ作らないように。
--
-- 널 허용 · 부분 유니크(키가 있는 행만) — 옛 주문 · 키 없이 오는 요청은 그대로다. 성립하지 않아 지운 주문(discardUnplaced)은
-- 행째 없어지므로 키도 풀린다.
--
-- ROLLBACK-SAFE: 널 허용 열 · 인덱스 추가뿐이다. 옛 코드는 이 열을 읽지 않는다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS create_key varchar(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_customer_create_key
    ON orders.orders (customer_id, create_key) WHERE create_key IS NOT NULL;
