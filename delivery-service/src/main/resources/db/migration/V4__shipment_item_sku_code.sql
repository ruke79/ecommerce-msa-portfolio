-- 포장 목록의 SKU — 상자에 넣을 것을 코드 하나로(catalog V17 → 주문 품목 V24 → 여기).
--
-- 창고는 이름이 아니라 코드로 집는다. "러닝화 A" 는 색상 · 사이즈마다 다른 물건이라 이름만으로는 어느 칸에서 꺼낼지
-- 모른다. 주문 품목이 복사해 둔 값을 배송을 만들 때 다시 복사한다(상품명 스냅샷과 같은 이유).
-- 더하기만 한다(NULL 허용) — 이 열 이전의 배송과, SKU 가 없던 주문의 배송은 NULL 이다.
ALTER TABLE delivery.shipment_item ADD COLUMN IF NOT EXISTS sku_code varchar(96);
