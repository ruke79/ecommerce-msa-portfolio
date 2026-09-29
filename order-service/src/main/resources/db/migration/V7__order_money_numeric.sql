-- ROLLBACK-UNSAFE: integer → numeric 으로 컬럼 형을 바꾼다. 되돌린 옛 이미지(int 필드)는
--   Hibernate validate 에서 "wrong column type encountered … expected integer" 로 기동
--   실패한다. 대비책: 되돌려야 하면 아래 네 줄을 `TYPE integer` 로 되돌리는 마이그레이션을
--   먼저 적용한다(소수는 그때 잘린다 — 이 마이그레이션 이후 생긴 주문의 센트 단위가
--   사라지므로 되돌리기 전에 그 주문들을 확인할 것).
--
-- 주문 금액의 소수점 — Order.total 이 int 라 $59.98 이 59 로 저장됐다(docs/35, docs/49 §6).
--
-- 청구액(charged_amount)은 이미 numeric(19,2) 다(V6). 그런데 그 청구액은 **잘린 total** 에서
-- 환산됐으므로 같은 오차를 물려받았다 — 통화 문제가 아니라 그 이전의 문제였다. 네 컬럼을
-- 함께 옮긴다. 넷이 한 식(total = totalBeforeDiscount − 할인, shipping ⊂ total)으로
-- 묶여 있어 하나만 옮기면 화면의 세 줄이 맞지 않는다.
--
-- 값은 보존된다: PostgreSQL 은 integer → numeric 을 암시적으로 바꾸고(USING 불필요),
-- 59 는 59.00 이 된다. NOT NULL 은 그대로다.
--
-- 옛 주문의 잘린 센트는 **복구하지 않는다.** 얼마가 잘렸는지 어디에도 남아 있지 않다.

ALTER TABLE orders.orders ALTER COLUMN total                 TYPE numeric(19,2);
ALTER TABLE orders.orders ALTER COLUMN total_before_discount TYPE numeric(19,2);
ALTER TABLE orders.orders ALTER COLUMN shipping_price        TYPE numeric(19,2);
ALTER TABLE orders.orders ALTER COLUMN tax_price             TYPE numeric(19,2);
