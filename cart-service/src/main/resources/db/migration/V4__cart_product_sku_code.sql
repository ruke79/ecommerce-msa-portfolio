-- 장바구니 줄의 SKU — 사이즈 한 줄의 재고 단위 코드(catalog V17, 예: FAS-100042-XL).
--
-- 줄의 식별은 지금처럼 (상품, 색상, 사이즈 이름)이다 — 이 열은 식별자가 아니라 **기록**이다. 담을 때 catalog 가
-- 알려 준 코드를 적어 두고, 주문(/checkout)이 그대로 주문 품목으로 옮긴다. 창고 · 택배 · 회계가 "무엇이 나갔나"를
-- 코드 하나로 읽게 하려는 것이다.
--
-- 더하기만 한다(NULL 허용). 이 열이 생기기 전에 담긴 줄은 NULL 이고, 주문으로 넘길 때 catalog 에서 채운다.
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS sku_code varchar(96);
