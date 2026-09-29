-- 주문 품목의 SKU — 무엇이 나갔는지를 코드 하나로(catalog V17, 예: FAS-100042-XL).
--
-- 주문 시점의 값을 **복사해 둔다.** 품목의 이름 · 가격을 복사해 두는 것과 같은 이유다 — 뒤에 상품이 바뀌어도 이 주문에서
-- 나간 것은 바뀌지 않는다. 배송(shipment_item)이 이 값을 받아 포장 목록에 적는다.
--
-- 식별자가 아니다: 재고 차감 · 예약 · 반품은 여전히 (상품, 색상, 사이즈 이름)으로 움직인다(product-update Avro 그대로).
-- 더하기만 한다(NULL 허용) — 이 열 이전의 주문은 NULL 이다.
ALTER TABLE orders.order_product ADD COLUMN IF NOT EXISTS sku_code varchar(96);
