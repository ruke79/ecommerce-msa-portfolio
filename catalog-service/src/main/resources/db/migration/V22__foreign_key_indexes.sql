-- 외래 키 열의 인덱스 넷 — 부모 행을 지울 때 Postgres 는 지운 행마다 자식 표에 "아직 가리키는 행이 있나" 를 묻는다.
--
-- 2026-10-19 데이터 규모 정리(ops/44 purge, 상품 100만, docs/83 §5): 상품 20만 개 묶음의 DELETE FROM product.product 가 끝나지 않았다.
-- product_subcategory 의 인덱스는 (subcategory_id, product_id) 뿐이라 product_id 로는 쓸 수 없어, 상품 한 행마다 분류 연결 100만 줄을
-- 훑었다(실행 계획 Seq Scan — ForeignKeyIndexMigrationTest). 관리자의 상품 삭제 · 카테고리 삭제도 같은 검사를 한다.
-- product_details · product_qa 는 작지만 같은 결함이고, subcategory(category_id) 는 카테고리를 지울 때.
-- product_sku(product_id) · product_size(skuproduct_id) 는 V15 에서 이미 넣었다.
--
-- ROLLBACK-SAFE: 인덱스만 더한다. 옛 코드도 그대로 돈다. 쓰기는 인덱스를 만드는 동안만 기다린다(운영 상품 수에서는 1초 안 ·
-- 데모 100만은 product_subcategory 수 초). 실습 DB 에 손으로 같은 이름을 먼저 만들었으면 IF NOT EXISTS 로 건너뛴다.
-- [JA] 外部キー列のインデックス四つ — 親を消すと消した行ごとに子のテーブルを照会する。product_subcategory には product_id が先頭の
--      インデックスがなく、商品一行ごとに 100 万行を走査して ops/44 purge の商品削除が終わらなかった。

CREATE INDEX IF NOT EXISTS idx_product_subcategory_product_id ON product.product_subcategory (product_id);
CREATE INDEX IF NOT EXISTS idx_product_details_product_id ON product.product_details (product_id);
CREATE INDEX IF NOT EXISTS idx_product_qa_product_id ON product.product_qa (product_id);
CREATE INDEX IF NOT EXISTS idx_subcategory_category_id ON product.subcategory (category_id);
