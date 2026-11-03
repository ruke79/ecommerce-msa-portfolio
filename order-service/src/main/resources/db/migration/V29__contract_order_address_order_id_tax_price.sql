-- contract — V25(expand)에서 쓰지 않게 된 두 열을 지운다(docs/72 §7 · docs/85 §8-1).
--
-- orders.order_address.order_id: 예전에 주소가 주문을 가리키던 반대 방향 외래 키. 4415e498565c(2026-10-24 묶음 릴리스)부터
--   주문 → 주소 한 방향만 쓴다(Order.shippingAddress · uq_orders_order_address). 열과 함께 그 외래 키 · idx_order_address ·
--   옛 유니크가 사라진다(PostgreSQL 은 열에 걸린 인덱스 · 제약을 같이 지운다).
-- orders.orders.tax_price: 아무도 채우지 않는 0 이었다(Order.taxPrice 제거, V25 에서 기본값 0).
-- [JA] V25(expand)で使わなくなった二列を削除する(contract)。外部キー · インデックス · 旧ユニークも列と一緒に消える。
--
-- ROLLBACK-UNSAFE: 열을 지운다 — 4415e498565c 전 이미지(b7c5053a45e8 등)는 order_address.order_id · orders.tax_price 를 쓴다
--   (Hibernate validate 에서 기동 실패). 대비책: 그 뒤의 이미지(4415e498565c · 6501d3fd8e60 · dc25b0f8a920 · 61f546a8b125 ·
--   d837605d7902 · d68f0307e979)는 두 열을 쓰지 않아 이미지만 되돌려도 뜬다 — ops/28 이 자동 복귀를 끄므로
--   `FORCE_UNSAFE_ROLLBACK=1 ops/28-deploy.sh <env> --rollback`. 그보다 옛 이미지로 가야 하면 먼저
--   `ALTER TABLE orders.order_address ADD COLUMN order_id bigint; ALTER TABLE orders.orders ADD COLUMN tax_price numeric(19,2) NOT NULL DEFAULT 0;`
--   (지운 order_id 값은 돌아오지 않는다 — 옛 코드의 주문 폐기가 그 주소를 남길 뿐 주문은 지워진다, V25 설명과 같다).
-- 락: DROP COLUMN 은 카탈로그만 바꾸지만 잠깐 ACCESS EXCLUSIVE 를 잡는다. 긴 질의 뒤에 줄을 서면 그동안 두 표의 읽기 · 쓰기가
--   모두 기다린다(락 대기열 FIFO) — 배포 직전 pg_stat_activity 확인, 백업(03:00 UTC, pg_dump 가 모든 표에 ACCESS SHARE)
--   창은 피한다(docs/85 §8-1). 짧은 lock_timeout + 재시도는 쓰지 않는다(X-3 — 29배 나빠졌다).

ALTER TABLE orders.order_address DROP COLUMN IF EXISTS order_id;
ALTER TABLE orders.orders DROP COLUMN IF EXISTS tax_price;
