-- SKU 를 유일하게 만들고, 자동 생성용 시퀀스를 마련한다.
--
-- V6(slug)과 같은 문제의 SKU 판이다: sku varchar(255) 에 아무 제약이 없었고, 시드 CSV
-- 자체가 509행 중 130종이 중복이었다("브랜드-카테고리-서브카테고리" 형식이라 같은 브랜드가
-- 같은 카테고리에 상품을 두 개 내면 무조건 겹친다). SKU 는 재고·주문이 참조하는 식별자다 —
-- 중복이면 "그 SKU" 가 어느 것인지 아무도 답할 수 없다.
--
-- 순서는 V6 과 동일하다: 기존 중복 정리 → 부분 UNIQUE 인덱스. 정리 규칙도 같다 —
-- 각 sku 에서 skuproduct_id 가 가장 작은 행이 원래 값을 지키고, 나머지는
-- "<sku>-dup-<skuproduct_id>" 가 된다. "-2" 대신 "-dup-<id>" 인 이유는 V6 주석 참조
-- (기존의 다른 값과 재충돌하면 인덱스 생성이 실패한다).

UPDATE product.product_sku p
SET sku = p.sku || '-dup-' || p.skuproduct_id
WHERE p.sku IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM product.product_sku q
      WHERE q.sku = p.sku
        AND q.skuproduct_id < p.skuproduct_id
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_sku_sku
    ON product.product_sku (sku)
    WHERE sku IS NOT NULL;

-- 자동 생성 번호의 원천 (ProductService.generateSku → "<접두사>-<번호 6자리>").
--
-- 애플리케이션에서 max+1 을 세면 레플리카 2개가 같은 번호를 받는다 — 시퀀스는 그럴 수
-- 없다. 100001 부터 시작하는 이유: 시드 데이터의 skuproduct_id(수백~수만)를 번호로 쓴
-- 값과 절대 겹치지 않는 대역을 쓰고, 6자리를 처음부터 채워 자릿수가 들쭉날쭉하지 않게
-- 한다. 롤백된 트랜잭션의 번호는 결번이 되는데 그것이 정상이다 — SKU 는 식별자이지
-- 일련번호가 아니므로 연속성을 약속하지 않는다.
CREATE SEQUENCE IF NOT EXISTS product.product_sku_seq START WITH 100001;
