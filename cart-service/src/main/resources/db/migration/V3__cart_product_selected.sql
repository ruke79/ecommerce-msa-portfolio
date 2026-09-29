-- 장바구니 줄의 '체크아웃에 고름' 표시 (docs/40 0-b 2단계 — 서버가 장바구니의 기준).
--
-- 예전에는 체크아웃(savecart)이 서버 장바구니를 **고른 줄로 통째로 바꿨다** — 고르지 않은 줄은 서버에서 사라지고, 담기·수량·삭제는
-- 브라우저(redux)에만 있었다. 이제 담기·수량·삭제가 서버에 바로 반영되고, 체크아웃은 줄을 지우지 않고 고른 줄에 표시만 한다.
-- 주문(order-service 가 읽는 /checkout)은 고른 줄로만 만들어진다.
--
-- 더하기만 한다(기본값 true — 지금 있는 줄은 모두 '고른 것', 예전 의미 그대로). 되돌릴 때 이 열은 무시된다.
ALTER TABLE cart.cart_product ADD COLUMN IF NOT EXISTS selected boolean NOT NULL DEFAULT true;
