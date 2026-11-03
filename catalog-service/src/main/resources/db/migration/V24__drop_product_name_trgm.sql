-- contract — 옛 이름 검색 인덱스(V21 의 idx_product_name_trgm, lower(name))를 지운다. 이름 검색은 V23 부터
-- product.search_key(name)(NFKC + 소문자)의 식 인덱스 idx_product_name_search_key_trgm 을 쓴다 — 6501d3fd8e60(2026-10-24)부터
-- lower(name) 으로 이름을 찾는 질의가 없다. 남겨 두면 상품 쓰기마다 같은 크기의 GIN 을 하나 더 고친다(데모 100만에서 두 번째로 큰 인덱스).
-- [JA] 旧名前検索インデックス(V21、lower(name))を削除する。名前検索は V23 の search_key の式インデックスを使う。
--
-- CONCURRENTLY: 보통의 DROP INDEX 는 product 표에 ACCESS EXCLUSIVE 를 잡는다 — 필터 메뉴 계산(최대 600초) · 검색(5초) 뒤에
--   줄을 서면 그동안 상품 읽기가 모두 멈춘다(락 대기열 FIFO, docs/85 §8-1). CONCURRENTLY 는 읽기 · 쓰기를 막지 않고 기다린다.
--   트랜잭션 안에서는 쓸 수 없다 — Flyway(11.7.2)는 이 문장을 알아보고 이 파일을 트랜잭션 밖에서 돌린다(.sql.conf 불필요,
--   ProductNameTrgmDropMigrationTest). 그래서 이 파일에는 다른 문장을 섞지 않는다(섞으면 Flyway 가 거절한다 — mixed=false).
-- ROLLBACK-SAFE: 인덱스만 지운다 — 되돌린 이미지도 그대로 뜬다(6501d3fd8e60 전 이미지는 이름 검색이 느려질 뿐; 되살리기는 V21 의
--   CREATE INDEX 문장).

DROP INDEX CONCURRENTLY IF EXISTS product.idx_product_name_trgm;
