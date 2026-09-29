-- 주문 상태 CHECK 제약에 REFUNDED 가 없었다 (docs/49 §8, 가드 B-55).
--
-- 베이스라인(V2)의 check (order_status in (...)) 는 그 시점의 OrderStatus 여덟 값이다. V5 에서
-- 환불을 붙이며 enum 에 REFUNDED 를 더했는데 제약은 그대로였다. Hibernate validate 는 CHECK 를
-- 보지 않으므로 아무 데서도 드러나지 않았고, 첫 전액 환불에서
--
--     ERROR: new row for relation "orders" violates check constraint "orders_order_status_check"
--
-- 로 터질 참이었다 — pay-service 의 payment_type 이 실제로 그렇게 터진 것을 보고 찾았다.
--
-- 되돌리기는 안전하다 — 허용 범위를 넓히기만 한다.

DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'orders.orders'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) ILIKE '%order_status%'
    LOOP
        EXECUTE format('ALTER TABLE orders.orders DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE orders.orders
    ADD CONSTRAINT orders_order_status_check
    CHECK (order_status IN ('NOT_PROCESSED', 'PAID', 'UNPAID', 'PROCESSING', 'DISPATCHED',
                            'DELIVERED', 'CANCELED', 'COMPLETED', 'REFUNDED'));
