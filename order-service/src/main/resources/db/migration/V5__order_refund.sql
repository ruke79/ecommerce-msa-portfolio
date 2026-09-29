-- 환불 — 주문에 제공자의 환불 참조와 시각을 남긴다.
--
-- 둘 다 nullable 이고 기본값이 없다. 추가만 하므로 되돌려도 옛 코드가 깨지지 않는다
-- (옛 엔티티는 이 컬럼을 모르고, Hibernate validate 는 모르는 컬럼을 문제 삼지 않는다).
--
-- refund_id 에 유니크를 걸지 않는 이유: 포트원 V1 은 별도 환불 id 를 주지 않아 결제 참조
-- (imp_uid)를 그대로 쓴다. 그 값은 이미 (payment_provider, provider_payment_id) 유니크로
-- 한 주문에만 붙어 있으므로, 환불 참조가 두 주문에 걸릴 수 없다.

ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS refund_id   varchar(255);
ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS refunded_at timestamp;
