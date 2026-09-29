-- 상품 상태 — 수정·삭제·품절 처리의 기반.
--
-- 지금까지 상품은 만들 수만 있었다. 오타 하나 고치는 것도, 내리는 것도 DB 직접 수정이었다.
-- 상태 하나로 삭제와 품절을 같이 다룬다:
--
--   ACTIVE    정상 판매
--   SOLD_OUT  목록·상세에 보이지만 장바구니에 담기지 않는다 (catalog 의 cart 정보 조회가
--             null 을 돌려줘 담기가 실패한다)
--   DELETED   어디에도 보이지 않는다. 엔티티의 @SQLRestriction 이 모든 JPA 조회에서
--             자동으로 걸러낸다 — 목록 쿼리 수십 개를 하나씩 고치는 대신 한 곳에서 막는다
--
-- ⚠️ DELETED 는 **애플리케이션에서는 되돌릴 수 없다** — @SQLRestriction 때문에 JPA 로는
--    그 행을 읽을 수조차 없다. 이것은 의도다: 삭제를 쉽게 되돌릴 수 있으면 "지웠다"는
--    말을 믿을 수 없게 된다. 정말 되돌려야 하면 SQL 로:
--      UPDATE product.product SET status = 'ACTIVE' WHERE product_id = ...;
--
-- 행을 지우지 않는(soft delete) 이유: product_sku · 주문 이력 · 리뷰가 이 행을 참조한다.
-- 물리 삭제는 참조 무결성을 깨거나 CASCADE 로 이력까지 지운다.

ALTER TABLE product.product
    ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'ACTIVE';

-- CHECK 는 별도 문장으로 — ADD COLUMN IF NOT EXISTS 와 합치면 컬럼이 이미 있을 때
-- 제약만 또 붙이려다 실패한다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_product_status'
    ) THEN
        ALTER TABLE product.product
            ADD CONSTRAINT chk_product_status
            CHECK (status IN ('ACTIVE', 'SOLD_OUT', 'DELETED'));
    END IF;
END $$;
