-- 주문 ↔ 배송지의 이중 외래 키를 한쪽(주문)으로 — expand 단계(docs/62 §18). 세금 열의 기본값.
--
-- 🐛 주문과 배송지가 서로를 가리켰다: orders.order_address_id → order_address, order_address.order_id → orders.
--    그래서 주문 하나를 만들 때 "주문 INSERT → 주소 INSERT → 주문 UPDATE(주소 id)" 세 번을 썼고, 지울 때는 한쪽을 먼저
--    끊어야 했으며(detachAddressOfUnplaced), 두 값이 어긋나도 막는 것이 없었다.
--    이제 주문이 주소를 가리키는 한 방향만 쓴다(Order.shippingAddress). 주소는 주문보다 먼저 저장된다 — INSERT 두 번.
-- [JA] 注文 ↔ 配送先が互いを指す二重の外部キーだった。注文 → 住所の一方向だけにする(expand)。
--
-- 이 릴리스: order_address.order_id 는 남기되 비워도 되게(새 코드는 쓰지 않는다). 주문당 주소 하나는 orders 쪽 유니크로.
-- 다음 릴리스(contract): order_address.order_id · 그 외래 키 · 인덱스를 지운다 — 이 버전이 안정된 뒤에(되돌린 옛 이미지는
-- 그 열을 쓴다).
-- 되돌리기: 이미지만 되돌려도 된다 — 옛 코드는 order_id 를 계속 채운다. 단 새 코드가 만든 주문의 주소는 order_id 가 비어
-- 있어 옛 코드의 주문 폐기(deleteAddressOfUnplaced, order_id 기준)가 그 주소를 남긴다 — 고아 주소 행일 뿐 주문은 지워진다.

ALTER TABLE orders.order_address ALTER COLUMN order_id DROP NOT NULL;

-- 세금 열 — 아무도 채우지 않는 0 이었다(Order.taxPrice 제거). 새 코드는 쓰지 않으므로 기본값을 둔다. 열은 다음 릴리스에 지운다.
ALTER TABLE orders.orders ALTER COLUMN tax_price SET DEFAULT 0;

-- 주문당 주소 하나 — 예전에는 order_address.order_id 의 유니크가 지켰다. 이제 주문 쪽에서.
-- 이미 어긋난 데이터(같은 주소를 둘 이상의 주문이 가리킴)가 있으면 만들지 않고 알린다 — 마이그레이션을 실패시키지 않는다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT order_address_id FROM orders.orders
                    WHERE order_address_id IS NOT NULL
                    GROUP BY order_address_id HAVING count(*) > 1) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_order_address ON orders.orders (order_address_id);
    ELSE
        RAISE NOTICE '같은 배송지를 가리키는 주문이 있어 uq_orders_order_address 를 만들지 않았다 — 확인 후 다시 만들 것';
    END IF;
END $$;
