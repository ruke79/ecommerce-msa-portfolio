-- 카테고리로 거르는 상품 질의용 인덱스 — product.product (category_id, product_id).
--
-- 2026-10-19 데이터 규모 측정(상품 100만, docs/83 §5): 카테고리 검색의 개수 세기 · 필터 메뉴의 브랜드 DISTINCT 가
-- category_id 를 맨 뒤 열로 가진 idx_product(name, brand, slug, category_id) 를 <b>통째로</b> 훑었다 — books(13만 개) 한 번에
-- 92,226쪽(≈ 720MB) · 93 ~ 105ms. 단독으로는 빠르지만 첫 쪽 검색마다 · 메뉴 질의마다 PostgreSQL 이 그만큼 읽었다.
-- (category_id, product_id) 면 개수 세기는 그 카테고리 범위만, 목록(ORDER BY product_id DESC)도 같은 인덱스로 끝난다.
--
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드는 이 인덱스를 몰라도 그대로 돈다. 쓰기는 인덱스를 만드는 동안만 기다린다
-- (운영 상품 수에서는 1초 안 · 데모 100만은 수 초).
-- [JA] カテゴリで絞る商品照会用のインデックス。以前は category_id が末尾の idx_product を丸ごと走査していた(books 13 万件で 720MB)。

CREATE INDEX IF NOT EXISTS idx_product_category ON product.product (category_id, product_id);
