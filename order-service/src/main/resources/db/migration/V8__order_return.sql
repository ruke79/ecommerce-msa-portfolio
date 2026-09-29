-- 반품 입고와 재고 복원 — 주문에 셋을 남긴다(docs/49 §10).
--
--   awaiting_return    발송된 뒤 환불돼 **물건이 돌아와야 하는** 주문. 관리자 화면의 "반품 대기".
--   return_received_at 관리자가 입고를 기록한 시각.
--   stock_restored_at  catalog 에 재고 복원 이벤트(product-update, restock=true)를 보낸 시각.
--                      null 이면 아직이거나, 파손 등으로 복원하지 않기로 한 것.
--
-- 재고 복원 시점은 둘이다: 발송 전 환불이면 환불 즉시(물건이 안 나갔다), 발송된 뒤면
-- 반품이 입고된 때. 어느 쪽이든 stock_restored_at 이 한 번만 찍히고, catalog 쪽도
-- eventId(restock-<orderId>)로 한 번만 반영한다.
--
-- 추가만 한다. awaiting_return 은 NOT NULL 이지만 DEFAULT 가 있어 옛 이미지의 INSERT 도
-- 통과한다(옛 엔티티는 이 컬럼을 모르고 validate 는 모르는 컬럼을 문제 삼지 않는다).

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS awaiting_return    boolean NOT NULL DEFAULT false;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS return_received_at timestamp;
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS stock_restored_at  timestamp;
