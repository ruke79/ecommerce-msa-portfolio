-- slug 를 유일하게 만든다.
--
-- slug 는 상세 페이지 URL 이다 (GET /api/product/{slug}). 그런데 지금까지 아무 제약이
-- 없어서, 같은 이름을 두 번 등록하면 findBySlug 가 여러 건을 돌려주고 **어느 상품이
-- 보일지는 우연이 정했다.** 애플리케이션(ProductService.uniqueSlug)이 충돌을 피해
-- "-2" 접미사를 붙이지만, 제약 없는 방어는 경쟁 조건 하나로 뚫린다 — DB 가 최후
-- 방어선이어야 한다.
--
-- ⚠️ 제약을 걸기 **전에** 기존 중복을 정리해야 한다. 시드 CSV 를 여러 번 적재했거나
--    이 제약 이전에 같은 이름을 등록한 DB 에는 이미 중복이 있을 수 있고, 그 상태로
--    UNIQUE 를 만들면 마이그레이션 자체가 실패해 서비스가 기동하지 못한다.
--    정리 규칙: 각 slug 에서 product_id 가 가장 작은 행(먼저 만들어진 것)이 원래
--    slug 를 지키고, 나머지는 "<slug>-dup-<product_id>" 로 바꾼다. 어떤 행도 지우지
--    않는다.
--
--    접미사가 "-2" 가 아니라 "-dup-<id>" 인 이유: "-2" 는 **다른 기존 slug 와 다시
--    충돌할 수 있다.** "shoe"(id 1,2) 와 별개 상품 "shoe-2"(id 3) 가 공존하는 DB 에서
--    id 2 를 "shoe-2" 로 바꾸면 id 3 과 부딪혀 아래 인덱스 생성이 실패한다.
--    "-dup-<id>" 는 id 가 유일하므로 정리 결과끼리도, 기존 값과도 부딪히지 않는다
--    (부딪히려면 정확히 "<slug>-dup-<그id>" 라는 slug 가 이미 있어야 한다).

UPDATE product.product p
SET slug = p.slug || '-dup-' || p.product_id
WHERE p.slug IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM product.product q
      WHERE q.slug = p.slug
        AND q.product_id < p.product_id
  );

-- NULL 은 여러 개 있어도 된다(부분 인덱스). slug 없는 행을 막는 것은 이 제약의 일이
-- 아니다 — 그건 애플리케이션 검증의 몫이다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_slug
    ON product.product (slug)
    WHERE slug IS NOT NULL;
