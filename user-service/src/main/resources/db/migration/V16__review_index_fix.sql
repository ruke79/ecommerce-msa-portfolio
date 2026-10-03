-- 리뷰 인덱스를 고친다 — 긴 한국어 리뷰가 저장되지 않던 것(docs/62).
-- [JA] レビューのインデックスを直す — 長い韓国語のレビューが保存できなかった。
--
-- ROLLBACK-SAFE: 인덱스만 바꾼다. 옛 이미지는 이 인덱스를 쓰지 않는다(Hibernate validate 는 인덱스를 보지 않는다).
--
-- 🐛 V2 의 idx_review (rating, review, size, fit) 에 review 본문이 들어 있다. V14 가 본문을 varchar(2000) 으로 넓힌 뒤로,
--    한글은 UTF-8 로 글자당 3바이트라 2,000자면 약 6KB — B-tree 행 한도(2,704바이트)를 넘어 INSERT 가 거절됐다
--    ("index row size 6024 exceeds btree version 4 maximum 2704", UserServiceApplicationTests 가 실제 PostgreSQL 로 재현).
--    이 인덱스를 타는 쿼리는 하나도 없다(ReviewRepository 는 product_id 로만 찾는다).
-- 대신 실제로 쓰는 product_id 에 인덱스를 둔다 — 상품 상세의 리뷰 목록 · 평점 평균이 이 조건이다.
DROP INDEX IF EXISTS users.idx_review;
CREATE INDEX IF NOT EXISTS idx_review_product ON users.review (product_id);
